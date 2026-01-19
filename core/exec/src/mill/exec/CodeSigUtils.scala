package mill.exec

import mill.api.MillException
import mill.api.Task
import mill.internal.SpanningForest

import java.lang.reflect.Method

object CodeSigUtils {
  def precomputeMethodNamesPerClass(transitiveNamed: Seq[Task.Named[?]])
      : (Map[Class[?], IndexedSeq[Class[?]]], Map[Class[?], Map[String, Method]]) =
    SpanningForest.precomputeMethodNamesPerClass(transitiveNamed)

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
    val (methodClass, encodedTaskName) =
      SpanningForest.computeMethodClassAndName(namedTask, classToTransitiveClasses, allTransitiveClassMethods)

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
