package mill.exec

import mill.api.{BuildInfo, MillException}
import mill.api.{Task, Segment}

import scala.reflect.NameTransformer.encode
import java.lang.reflect.Method

object CodeSigUtils {
  def precomputeMethodNamesPerClass(transitiveNamed: Seq[Task.Named[?]])
      : (Map[Class[?], IndexedSeq[Class[?]]], Map[Class[?], Map[String, Method]]) = {

    def resolveTransitiveParents(c: Class[?]): Iterable[Class[?]] = {
      val seen = collection.mutable.LinkedHashSet(c) // Maintain first-seen ordering
      val queue = collection.mutable.Queue(c)
      while (queue.nonEmpty) {
        val current = queue.dequeue()
        for (
          next <- Option(current.getSuperclass) ++ current.getInterfaces if !seen.contains(next)
        ) {
          seen.add(next)
          queue.enqueue(next)
        }
      }

      seen
    }

    val classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]] = transitiveNamed
      .map { case namedTask: Task.Named[?] => namedTask.ctx.enclosingCls }
      .distinct
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

  def constructorHashSignatures(codeSignatures: Map[String, Int])
      : Map[String, Seq[(String, Int)]] =
    codeSignatures
      .toSeq
      .collect { case (method @ s"$prefix#<init>($_)void", hash) => (prefix, method, hash) }
      .groupMap(_._1)(t => (t._2, t._3))

  def codeSigForTask(
      namedTask: => Task.Named[?],
      classToTransitiveClasses: => Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: => Map[Class[?], Map[String, java.lang.reflect.Method]],
      codeSignatures: => Map[String, Int],
      constructorHashSignatures: => Map[String, Seq[(String, Int)]]
  ): Iterable[Int] = {

    val superTaskName = namedTask.ctx.segments.value.collectFirst {
      case Segment.Label(s"$v.super") => v
    }

    val encodedTaskName = superTaskName match {
      case Some(v) => v
      case None => encode(namedTask.ctx.segments.last.pathSegments.head)
    }

    // For .super tasks (e.g., qux.quxCommand.super.QuxModule), we need to look up
    // the signature for the super class (QuxModule), not the subclass (qux$).
    // The super class name is in the last segment.
    val superClassName = superTaskName.map(_ => namedTask.ctx.segments.last.pathSegments.head)

    def classNameMatches(cls: Class[?], simpleName: String): Boolean = {
      val clsName = cls.getName
      // Match either "package$ClassName" (for nested classes) or "package.ClassName" (for top-level)
      clsName.endsWith("$" + simpleName) || clsName.endsWith("." + simpleName)
    }

    val methodOpt = for {
      parentCls <- classToTransitiveClasses(namedTask.ctx.enclosingCls).iterator
      // For .super tasks, only consider the class that matches the super class name
      if superClassName.forall(scn => classNameMatches(parentCls, scn))
      m <- allTransitiveClassMethods(parentCls).get(encodedTaskName)
    } yield m

    val methodClass = methodOpt
      .nextOption()
      .getOrElse(throw new MillException(
        s"Could not detect the parent class of task ${namedTask}. " +
          s"Please report this at ${BuildInfo.millReportNewIssueUrl} . "
      ))
      .getDeclaringClass.getName

    val expectedName = methodClass + "#" + encodedTaskName + "()mill.api.Task$Simple"
    val expectedName2 = methodClass + "#" + encodedTaskName + "()mill.api.Task$Command"

    // We not only need to look up the code hash of the task method being called,
    // but also the code hash of the constructors required to instantiate the Module
    // that the task is being called on. This can be done by walking up the nested
    // modules and looking at their constructors (they're `object`s and should each
    // have only one)
    val allEnclosingModules = Vector.unfold(namedTask.ctx) {
      case null => None
      case ctx =>
        ctx.enclosingModule match {
          case null => None
          case m: mill.api.Module => Some((m, m.moduleCtx))
          case unknown =>
            throw new MillException(s"Unknown ctx of task ${namedTask}: $unknown")
        }
    }

    val constructorHashes = allEnclosingModules
      .map(m =>
        constructorHashSignatures.get(m.getClass.getName) match {
          case Some(Seq((_, hash))) => hash
          case Some(multiple) => throw new MillException(
              s"Multiple constructors found for module $m: ${multiple.mkString(",")}"
            )
          case None => 0
        }
      )

    codeSignatures.get(expectedName) ++
      codeSignatures.get(expectedName2) ++
      constructorHashes
  }
}
