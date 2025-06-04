package mill.bsp.worker

import java.util.concurrent.CompletableFuture

import scala.jdk.CollectionConverters.*

import ch.epfl.scala.bsp4j.{
  JavaBuildServer,
  JavacOptionsItem,
  JavacOptionsParams,
  JavacOptionsResult
}
import mill.api.internal.JavaModuleApi
import mill.api.internal.bsp.BspModuleApi
import mill.bsp.worker.Utils.sanitizeUri

private trait MillJavaBuildServer extends JavaBuildServer { this: MillBuildServer =>

  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    handlerTasks(
      targetIds = _ => javacOptionsParams.getTargets.asScala,
      tasks = {
        case m: (JavaModuleApi & BspModuleApi) =>
          m.bspJavaModule().bspBuildTargetJavacOptions(
            sessionInfo.clientType.mergeResourcesIntoClasses,
            sessionInfo.clientWantsSemanticDb
          )
      },
      requestDescription = "Getting javac options of {}"
    ) {
      // We ignore all non-JavaModule
      case (ev, state, id, m: JavaModuleApi, f) =>
        val res = f(ev)
        new JavacOptionsItem(
          id,
          res.javacOptions.asJava,
          res.classpath.asJava,
          sanitizeUri(res.classesPath)
        )

      case _ => ???
    } {
      new JavacOptionsResult(_)
    }
}
