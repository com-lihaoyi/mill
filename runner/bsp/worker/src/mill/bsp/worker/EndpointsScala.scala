package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  ScalaBuildServer,
  ScalaMainClass,
  ScalaMainClassesItem,
  ScalaMainClassesParams,
  ScalaMainClassesResult,
  ScalaTestClassesItem,
  ScalaTestClassesParams,
  ScalaTestClassesResult,
  ScalacOptionsItem,
  ScalacOptionsParams,
  ScalacOptionsResult
}
import mill.api.daemon.internal.{ScalaModuleApi, TestModuleApi}
import mill.bsp.worker.Utils.sanitizeUri

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*

private trait EndpointsScala extends ScalaBuildServer with EndpointsApi {

  override def buildTargetScalacOptions(p: ScalacOptionsParams)
      : CompletableFuture[ScalacOptionsResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: ScalaModuleApi =>
          m.bspJavaModule().bspBuildTargetScalacOptions(
            sessionInfo.clientType.mergeResourcesIntoClasses,
            enableJvmCompileClasspathProvider = sessionInfo.enableJvmCompileClasspathProvider,
            clientWantsSemanticDb = sessionInfo.clientWantsSemanticDb
          )
      },
      requestDescription = "Getting scalac options of {}",
      originId = ""
    ) { ctx =>
      val (allScalacOptions, compileClasspath, classesPathTask) = ctx.value
      new ScalacOptionsItem(
        ctx.id,
        allScalacOptions.asJava,
        compileClasspath(ctx.evaluator).asJava,
        sanitizeUri(classesPathTask(ctx.evaluator))
      )

    } { (values, _) =>
      new ScalacOptionsResult(values.asScala.sortBy(_.getTarget.getUri).asJava)
    }

  override def buildTargetScalaMainClasses(p: ScalaMainClassesParams)
      : CompletableFuture[ScalaMainClassesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = { case m: ScalaModuleApi => m.bspJavaModule().bspBuildTargetScalaMainClasses },
      requestDescription = "Getting main classes of {}",
      originId = p.getOriginId
    ) { ctx =>
      // We find all main classes, although we could also find only the configured one
      val mainClasses = ctx.value.classes
      // val mainMain = m.mainClass().orElse(if(mainClasses.size == 1) mainClasses.headOption else None)
      val items = mainClasses.map { mc =>
        val scalaMc = new ScalaMainClass(mc, Seq().asJava, ctx.value.forkArgs.asJava)
        scalaMc.setEnvironmentVariables(ctx.value.forkEnv.map(e => s"${e._1}=${e._2}").toSeq.asJava)
        scalaMc
      }
      new ScalaMainClassesItem(ctx.id, items.asJava)

    } { (values, _) =>
      new ScalaMainClassesResult(values)
    }

  override def buildTargetScalaTestClasses(p: ScalaTestClassesParams)
      : CompletableFuture[ScalaTestClassesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: (ScalaModuleApi & TestModuleApi) => m.bspBuildTargetScalaTestClasses
      },
      requestDescription = "Getting test classes of {}",
      originId = p.getOriginId
    ) { ctx =>
      val (frameworkName, classes) = ctx.value
      val item = new ScalaTestClassesItem(ctx.id, classes.asJava)
      item.setFramework(frameworkName)
      item
    } { (values, _) =>
      new ScalaTestClassesResult(values)
    }

}
