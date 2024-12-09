package mill.eval

import mill.api.{BuildInfo, MillException}
import mill.define.{NamedTask, Task}
import mill.util.MultiBiMap

import scala.reflect.NameTransformer.encode
import java.lang.reflect.Method

object CodeSigUtils {
  def precomputeMethodNamesPerClass(sortedGroups: MultiBiMap[Terminal, Task[_]])
      : (Map[Class[_], IndexedSeq[Class[_]]], Map[Class[_], Map[String, Method]]) = {
    def resolveTransitiveParents(c: Class[_]): Iterator[Class[_]] = {
      Iterator(c) ++
        Option(c.getSuperclass).iterator.flatMap(resolveTransitiveParents) ++
        c.getInterfaces.iterator.flatMap(resolveTransitiveParents)
    }

    val classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]] = sortedGroups
      .values()
      .flatten
      .collect { case namedTask: NamedTask[?] => namedTask.ctx.enclosingCls }
      .map(cls => cls -> resolveTransitiveParents(cls).toVector)
      .toMap

    val allTransitiveClasses = classToTransitiveClasses
      .iterator
      .flatMap(_._2)
      .toSet

    val allTransitiveClassMethods: Map[Class[?], Map[String, java.lang.reflect.Method]] =
      allTransitiveClasses
        .map { cls =>
          val cMangledName = cls.getName.replace('.', '$')
          cls -> cls.getDeclaredMethods
            .flatMap { m =>
              Seq(
                m.getName -> m,
                // Handle scenarios where private method names get mangled when they are
                // not really JVM-private due to being accessed by Scala nested objects
                // or classes https://github.com/scala/bug/issues/9306
                m.getName.stripPrefix(cMangledName + "$$") -> m,
                m.getName.stripPrefix(cMangledName + "$") -> m
              )
            }.toMap
        }
        .toMap

    (classToTransitiveClasses, allTransitiveClassMethods)
  }

  def constructorHashSignatures(methodCodeHashSignatures: Map[String, Int])
      : Map[String, Seq[(String, Int)]] =
    methodCodeHashSignatures
      .toSeq
      .collect { case (method @ s"$prefix#<init>($args)void", hash) => (prefix, method, hash) }
      .groupMap(_._1)(t => (t._2, t._3))

  def codeSigForTask(
      namedTask: => NamedTask[_],
      classToTransitiveClasses: => Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: => Map[Class[?], Map[String, java.lang.reflect.Method]],
      methodCodeHashSignatures: => Map[String, Int],
      constructorHashSignatures: => Map[String, Seq[(String, Int)]]
  ): Iterable[Int] = {

    val encodedTaskName = encode(namedTask.ctx.segment.pathSegments.head)

    val methodOpt = for {
      parentCls <- classToTransitiveClasses(namedTask.ctx.enclosingCls).iterator
      m <- allTransitiveClassMethods(parentCls).get(encodedTaskName)
    } yield m

    val methodClass = methodOpt
      .nextOption()
      .getOrElse(throw new MillException(
        s"Could not detect the parent class of target ${namedTask}. " +
          s"Please report this at ${BuildInfo.millReportNewIssueUrl} . "
      ))
      .getDeclaringClass.getName

    val name = namedTask.ctx.segment.pathSegments.last
    val expectedName = methodClass + "#" + name + "()mill.define.Target"
    val expectedName2 = methodClass + "#" + name + "()mill.define.Command"

    // We not only need to look up the code hash of the Target method being called,
    // but also the code hash of the constructors required to instantiate the Module
    // that the Target is being called on. This can be done by walking up the nested
    // modules and looking at their constructors (they're `object`s and should each
    // have only one)
    val allEnclosingModules = Vector.unfold(namedTask.ctx) {
      case null => None
      case ctx =>
        ctx.enclosingModule match {
          case null => None
          case m: mill.define.Module => Some((m, m.millOuterCtx))
          case unknown =>
            throw new MillException(s"Unknown ctx of target ${namedTask}: $unknown")
        }
    }

    val constructorHashes = allEnclosingModules
      .map(m =>
        constructorHashSignatures.get(m.getClass.getName) match {
          case Some(Seq((singleMethod, hash))) => hash
          case Some(multiple) => throw new MillException(
              s"Multiple constructors found for module $m: ${multiple.mkString(",")}"
            )
          case None => 0
        }
      )

    methodCodeHashSignatures.get(expectedName) ++
      methodCodeHashSignatures.get(expectedName2) ++
      constructorHashes
  }
}
