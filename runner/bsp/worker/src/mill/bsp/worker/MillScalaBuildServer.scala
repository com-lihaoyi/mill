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
import scala.jdk.CollectionConverters._

private trait MillScalaBuildServer extends ScalaBuildServer { this: MillBuildServer =>

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
    ) {
      case (
            ev,
            _,
            id,
            _,
            (allScalacOptions, compileClasspath, classesPathTask)
          ) =>
        new ScalacOptionsItem(
          id,
          allScalacOptions.asJava,
          compileClasspath(ev).asJava,
          sanitizeUri(classesPathTask(ev))
        )

    } { values =>
      new ScalacOptionsResult(values.asScala.sortBy(_.getTarget.getUri).asJava)
    }

  override def buildTargetScalaMainClasses(p: ScalaMainClassesParams)
      : CompletableFuture[ScalaMainClassesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = { case m: ScalaModuleApi => m.bspJavaModule().bspBuildTargetScalaMainClasses },
      requestDescription = "Getting main classes of {}",
      originId = p.getOriginId
    ) {
      case (_, _, id, _, res) =>
        // We find all main classes, although we could also find only the configured one
        val mainClasses = res.classes
        // val mainMain = m.mainClass().orElse(if(mainClasses.size == 1) mainClasses.headOption else None)
        val items = mainClasses.map { mc =>
          val scalaMc = new ScalaMainClass(mc, Seq().asJava, res.forkArgs.asJava)
          scalaMc.setEnvironmentVariables(res.forkEnv.map(e => s"${e._1}=${e._2}").toSeq.asJava)
          scalaMc
        }
        new ScalaMainClassesItem(id, items.asJava)

    } {
      new ScalaMainClassesResult(_)
    }

  override def buildTargetScalaTestClasses(p: ScalaTestClassesParams)
      : CompletableFuture[ScalaTestClassesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: ScalaModuleApi & TestModuleApi => m.bspBuildTargetScalaTestClasses
      },
      requestDescription = "Getting test classes of {}",
      originId = p.getOriginId
    ) {
      case (_, _, id, _, (frameworkName, classes)) =>
        val item = new ScalaTestClassesItem(id, classes.asJava)
        item.setFramework(frameworkName)
        item

      case (_, _, id, _, _) =>
        // Not a test module, so no test classes
        new ScalaTestClassesItem(id, Seq.empty[String].asJava)
    } {
      new ScalaTestClassesResult(_)
    }

}
