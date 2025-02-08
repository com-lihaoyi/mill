package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  JavaBuildServer,
  JavacOptionsItem,
  JavacOptionsParams,
  JavacOptionsResult
}
import mill.Task
import mill.bsp.worker.Utils.sanitizeUri
import mill.scalalib.{JavaModule, SemanticDbJavaModule}

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

private trait MillJavaBuildServer extends JavaBuildServer { this: MillBuildServer =>

  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    completableTasks(
      s"buildTargetJavacOptions ${javacOptionsParams}",
      targetIds = _ => javacOptionsParams.getTargets.asScala.toSeq,
      tasks = { case m: JavaModule =>
        val classesPathTask = m match {
          case sem: SemanticDbJavaModule if clientWantsSemanticDb =>
            sem.bspCompiledClassesAndSemanticDbFiles
          case _ => m.bspCompileClassesPath
        }
        Task.Anon {
          (
            classesPathTask(),
            m.javacOptions() ++ m.mandatoryJavacOptions(),
            m.bspCompileClasspath()
          )
        }
      }
    ) {
      // We ignore all non-JavaModule
      case (ev, state, id, m: JavaModule, (classesPath, javacOptions, bspCompileClasspath)) =>
        val pathResolver = ev.pathsResolver
        val options = javacOptions
        val classpath =
          bspCompileClasspath.map(_.resolve(pathResolver)).map(sanitizeUri)
        new JavacOptionsItem(
          id,
          options.asJava,
          classpath.iterator.toSeq.asJava,
          sanitizeUri(classesPath.resolve(pathResolver))
        )
        
      case _ => ???
    } {
      new JavacOptionsResult(_)
    }
}
