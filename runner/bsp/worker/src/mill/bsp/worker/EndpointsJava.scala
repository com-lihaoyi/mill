package mill.bsp.worker

import java.util.concurrent.CompletableFuture

import scala.jdk.CollectionConverters.*

import ch.epfl.scala.bsp4j.{
  JavaBuildServer,
  JavacOptionsItem,
  JavacOptionsParams,
  JavacOptionsResult
}
import mill.api.daemon.internal.JavaModuleApi
import mill.bsp.worker.Utils.sanitizeUri

private trait EndpointsJava extends JavaBuildServer with EndpointsApi {

  override def buildTargetJavacOptions(javacOptionsParams: JavacOptionsParams)
      : CompletableFuture[JavacOptionsResult] =
    handlerTasks(
      targetIds = _ => javacOptionsParams.getTargets.asScala,
      tasks = {
        // We ignore all non-JavaModule
        case m: JavaModuleApi =>
          m.bspJavaModule().bspBuildTargetJavacOptions(
            sessionInfo.clientType.mergeResourcesIntoClasses,
            sessionInfo.clientWantsSemanticDb
          )
      },
      requestDescription = "Getting javac options of {}",
      originId = ""
    ) { ctx =>
      val res = ctx.value(ctx.evaluator)
      new JavacOptionsItem(
        ctx.id,
        res.javacOptions.asJava,
        res.classpath.asJava,
        sanitizeUri(res.classesPath)
      )

    } { (values, _) =>
      new JavacOptionsResult(values)
    }
}
