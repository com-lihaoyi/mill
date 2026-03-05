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
import mill.api.daemon.internal.{JavaModuleApi, ScalaModuleApi, TestModuleApi}
import mill.bsp.worker.Utils.sanitizeUri

import java.util.concurrent.CompletableFuture
import scala.build.bsp.{
  ScalaScriptBuildServer,
  WrappedSourceItem,
  WrappedSourcesItem,
  WrappedSourcesParams,
  WrappedSourcesResult
}
import scala.jdk.CollectionConverters.*

private trait EndpointsScala extends ScalaBuildServer with ScalaScriptBuildServer
    with EndpointsApi {

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
    ) { (ctx, _) =>
      val (allScalacOptions, compileClasspath, classesPathTask) = ctx.value
      new ScalacOptionsItem(
        ctx.id,
        allScalacOptions.asJava,
        compileClasspath(ctx.evaluator).asJava,
        sanitizeUri(classesPathTask(ctx.evaluator))
      )

    } { (values, _, _) =>
      new ScalacOptionsResult(values.asScala.sortBy(_.getTarget.getUri).asJava)
    }

  override def buildTargetScalaMainClasses(p: ScalaMainClassesParams)
      : CompletableFuture[ScalaMainClassesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = { case m: ScalaModuleApi => m.bspJavaModule().bspBuildTargetScalaMainClasses },
      requestDescription = "Getting main classes of {}",
      originId = p.getOriginId
    ) { (ctx, _) =>
      // We find all main classes, although we could also find only the configured one
      val mainClasses = ctx.value.classes
      // val mainMain = m.mainClass().orElse(if(mainClasses.size == 1) mainClasses.headOption else None)
      val items = mainClasses.map { mc =>
        val scalaMc = new ScalaMainClass(mc, Seq().asJava, ctx.value.forkArgs.asJava)
        scalaMc.setEnvironmentVariables(ctx.value.forkEnv.map(e => s"${e._1}=${e._2}").toSeq.asJava)
        scalaMc
      }
      new ScalaMainClassesItem(ctx.id, items.asJava)

    } { (values, _, _) =>
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
    ) { (ctx, _) =>
      val (frameworkName, classes) = ctx.value
      val item = new ScalaTestClassesItem(ctx.id, classes.asJava)
      item.setFramework(frameworkName)
      item
    } { (values, _, _) =>
      new ScalaTestClassesResult(values)
    }

  private val userCodeStartMarker = "//SOURCECODE_ORIGINAL_CODE_START_MARKER\n"

  override def buildTargetWrappedSources(params: WrappedSourcesParams)
      : CompletableFuture[WrappedSourcesResult] =
    handlerTasks(
      targetIds = _ => params.getTargets.asScala,
      tasks = { case module: JavaModuleApi => module.bspJavaModule().bspBuildTargetWrappedSources },
      requestDescription =
        s"Getting wrapped sources of ${params.getTargets.asScala.map(_.getUri).mkString(", ")}",
      originId = ""
    ) { (ctx, logger) =>
      val items = ctx.value.flatMap {
        case (original, generated) =>
          val generatedContent = os.read(os.Path(generated))
          val idx = generatedContent.indexOf(userCodeStartMarker)
          if (idx >= 0) {
            val item = new WrappedSourceItem(
              original.toUri.toASCIIString,
              generated.toUri.toASCIIString
            )
            item.setTopWrapper(generatedContent.take(idx + userCodeStartMarker.length))
            item.setBottomWrapper("\n}")
            Seq(item)
          } else {
            logger.warn(s"User code start marker not found in $generated")
            Nil
          }
      }
      new WrappedSourcesItem(ctx.id, items.asJava)
    } { (items, _, _) =>
      new WrappedSourcesResult(items)
    }

}
