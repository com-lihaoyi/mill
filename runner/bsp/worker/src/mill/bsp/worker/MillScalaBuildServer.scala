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
import mill.api.internal.{TaskApi, JavaModuleApi, TestModuleApi}
import mill.bsp.worker.Utils.sanitizeUri

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

private trait MillScalaBuildServer extends ScalaBuildServer { this: MillBuildServer =>

  override def buildTargetScalacOptions(p: ScalacOptionsParams)
      : CompletableFuture[ScalacOptionsResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: JavaModuleApi =>
          m.bspBuildTargetScalacOptions(
            sessionInfo.enableJvmCompileClasspathProvider,
            sessionInfo.clientWantsSemanticDb
          )
      },
      requestDescription = "Getting scalac options of {}"
    ) {
      // We ignore all non-JavaModule
      case (
            ev,
            state,
            id,
            m: JavaModuleApi,
            (allScalacOptions, compileClasspath, classesPathTask)
          ) =>
        new ScalacOptionsItem(
          id,
          allScalacOptions.asJava,
          compileClasspath(ev).asJava,
          sanitizeUri(classesPathTask(ev))
        )
      case _ => ???
    } { values =>
      new ScalacOptionsResult(values.asScala.sortBy(_.getTarget.getUri).asJava)
    }

  override def buildTargetScalaMainClasses(p: ScalaMainClassesParams)
      : CompletableFuture[ScalaMainClassesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = { case m: JavaModuleApi => m.bspBuildTargetScalaMainClasses },
      requestDescription = "Getting main classes of {}"
    ) {
      case (ev, state, id, m: JavaModuleApi, (classes, forkArgs, forkEnv)) =>
        // We find all main classes, although we could also find only the configured one
        val mainClasses = classes
        // val mainMain = m.mainClass().orElse(if(mainClasses.size == 1) mainClasses.headOption else None)
        val items = mainClasses.map { mc =>
          val scalaMc = new ScalaMainClass(mc, Seq().asJava, forkArgs.asJava)
          scalaMc.setEnvironmentVariables(forkEnv.map(e => s"${e._1}=${e._2}").toSeq.asJava)
          scalaMc
        }
        new ScalaMainClassesItem(id, items.asJava)

      case (ev, state, id, _, _) => // no Java module, so no main classes
        new ScalaMainClassesItem(id, Seq.empty[ScalaMainClass].asJava)
    } {
      new ScalaMainClassesResult(_)
    }

  override def buildTargetScalaTestClasses(p: ScalaTestClassesParams)
      : CompletableFuture[ScalaTestClassesResult] =
    handlerTasks(
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: TestModuleApi => m.bspBuildTargetScalaTestClasses
      },
      requestDescription = "Getting test classes of {}"
    ) {
      case (ev, state, id, m: TestModuleApi, (frameworkName, classes)) =>
        val item = new ScalaTestClassesItem(id, classes.asJava)
        item.setFramework(frameworkName)
        item

      case (ev, state, id, _, _) =>
        // Not a test module, so no test classes
        new ScalaTestClassesItem(id, Seq.empty[String].asJava)
    } {
      new ScalaTestClassesResult(_)
    }

}
