package mill.bsp.worker

import ch.epfl.scala.bsp4j.{JavaBuildServer, JavacOptionsItem, JavacOptionsParams, JavacOptionsResult}
import mill.api.internal.{BspModuleApi, JavaModuleApi, TaskApi}
import mill.bsp.worker.Utils.sanitizeUri
import mill.bsp.worker.modules.BspJavaModule.given
import java.util.concurrent.CompletableFuture

import scala.jdk.CollectionConverters.*

import mill.scalalib.JavaModule

private trait MillJavaBuildServer extends JavaBuildServer { this: MillBuildServer =>

  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    handlerTasks(
      targetIds = _ => javacOptionsParams.getTargets.asScala,
      tasks = {
        case m: (JavaModuleApi & BspModuleApi) =>
          m.bspJavaModule.bspBuildTargetJavacOptions(sessionInfo.clientWantsSemanticDb)
        //          m.bspJavaModule.bspBuildTargetJavacOptions(sessionInfo.clientWantsSemanticDb)
        //        case m: JavaModule =>
      },
      requestDescription = "Getting javac options of {}"
    ) {
      // We ignore all non-JavaModule
      case (ev, state, id, m: JavaModuleApi, f) =>
        val (classesPath, javacOptions, classpath) = f(ev)
        new JavacOptionsItem(
          id,
          javacOptions.asJava,
          classpath.asJava,
          sanitizeUri(classesPath)
        )

      case _ => ???
    } {
      new JavacOptionsResult(_)
    }
}
