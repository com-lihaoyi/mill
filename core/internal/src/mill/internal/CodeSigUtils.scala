package mill.internal

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

  /**
   * Computes the declaring class name and encoded method name for a task.
   * Used by both code signature computation and invalidation tree building.
   */
  def methodClassAndName(
      namedTask: Task.Named[?],
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, java.lang.reflect.Method]]
  ): (String, String) = {
    val superTaskName = namedTask.ctx.segments.value.collectFirst {
      case Segment.Label(s"$v.super") => v
    }

    val encodedTaskName = superTaskName match {
      case Some(v) => v
      case None => encode(namedTask.ctx.segments.last.pathSegments.head)
    }

    // For .super tasks (e.g., qux.quxCommand.super.QuxModule), we need to look up
    // the signature for the super class (QuxModule), not the subclass (qux$).
    val superClassName = superTaskName.map(_ => namedTask.ctx.segments.last.pathSegments.head)

    def classNameMatches(cls: Class[?], simpleName: String): Boolean = {
      val clsName = cls.getName
      clsName.endsWith("$" + simpleName) || clsName.endsWith("." + simpleName)
    }

    val methodOpt = for {
      parentCls <- classToTransitiveClasses(namedTask.ctx.enclosingCls).iterator
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

    (methodClass, encodedTaskName)
  }

  /**
   * Returns the expected method signature patterns for a task.
   * Used for matching tasks to their method signatures in code signature data.
   */
  def taskMethodSignatures(
      namedTask: Task.Named[?],
      classToTransitiveClasses: Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: Map[Class[?], Map[String, java.lang.reflect.Method]]
  ): Seq[String] = {
    val (methodClass, encodedTaskName) =
      methodClassAndName(namedTask, classToTransitiveClasses, allTransitiveClassMethods)

    Seq(
      s"$methodClass#$encodedTaskName()mill.api.Task$$Simple",
      s"$methodClass#$encodedTaskName()mill.api.Task$$Command"
    )
  }

  /**
   * Returns all enclosing modules for a task, from innermost to outermost.
   * Used by both codeSigForTask and moduleAccessorSignatures.
   */
  def enclosingModules(namedTask: Task.Named[?]): Vector[mill.api.Module] = {
    Vector.unfold(namedTask.ctx) {
      case null => None
      case ctx =>
        ctx.enclosingModule match {
          case null => None
          case m: mill.api.Module => Some((m, m.moduleCtx))
          case _ => None
        }
    }
  }

  /**
   * Returns the module accessor signatures for a task's enclosing modules.
   * These are the signatures of methods that return the module containing this task.
   */
  def moduleAccessorSignatures(namedTask: Task.Named[?]): Seq[String] = {
    enclosingModules(namedTask).sliding(2).collect {
      case Vector(child, parent) =>
        val parentClass = parent.getClass.getName
        val childClass = child.getClass.getName
        child.moduleCtx.segments.value.lastOption match {
          case Some(Segment.Label(name)) => s"$parentClass#${encode(name)}()$childClass"
          case _ => return Nil
        }
    }.toSeq
  }

  def codeSigForTask(
      namedTask: => Task.Named[?],
      classToTransitiveClasses: => Map[Class[?], IndexedSeq[Class[?]]],
      allTransitiveClassMethods: => Map[Class[?], Map[String, java.lang.reflect.Method]],
      codeSignatures: => Map[String, Int],
      constructorHashSignatures: => Map[String, Seq[(String, Int)]]
  ): Iterable[Int] = {
    val sigs = taskMethodSignatures(namedTask, classToTransitiveClasses, allTransitiveClassMethods)

    // We not only need to look up the code hash of the task method being called,
    // but also the code hash of the constructors required to instantiate the Module
    // that the task is being called on. This can be done by walking up the nested
    // modules and looking at their constructors (they're `object`s and should each
    // have only one)
    val constructorHashes = enclosingModules(namedTask)
      .map(m =>
        constructorHashSignatures.get(m.getClass.getName) match {
          case Some(Seq((_, hash))) => hash
          case Some(multiple) => throw new MillException(
              s"Multiple constructors found for module $m: ${multiple.mkString(",")}"
            )
          case None => 0
        }
      )

    sigs.flatMap(codeSignatures.get) ++ constructorHashes
  }
}
