package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  JavaBuildServer,
  JavacOptionsItem,
  JavacOptionsParams,
  JavacOptionsResult
}
import mill.runner.api.{TaskApi, JavaModuleApi, SemanticDbJavaModuleApi}
import mill.bsp.worker.Utils.sanitizeUri

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

private trait MillJavaBuildServer extends JavaBuildServer { this: MillBuildServer =>

  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    completableTasks(
      s"buildTargetJavacOptions ${javacOptionsParams}",
      targetIds = _ => javacOptionsParams.getTargets.asScala.toSeq,
      tasks = { case m: JavaModuleApi =>
        m.bspBuildTargetJavacOptions(sessionInfo.clientWantsSemanticDb)
      }
    ) {
      // We ignore all non-JavaModule
      case (ev, state, id, m: JavaModuleApi, f) =>
        val (classesPath, javacOptions, classpath) = f(ev)
        new JavacOptionsItem(
          id,
          javacOptions.asJava,
          classpath.iterator.toSeq.asJava,
          sanitizeUri(classesPath)
        )

      case _ => ???
    } {
      new JavacOptionsResult(_)
    }
}
