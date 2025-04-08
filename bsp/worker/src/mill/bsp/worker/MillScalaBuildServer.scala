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
import mill.runner.api.{TaskApi, JavaModuleApi, TestModuleApi}
import mill.bsp.worker.Utils.sanitizeUri
import sbt.testing.Fingerprint

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._
import scala.util.chaining.scalaUtilChainingOps

private trait MillScalaBuildServer extends ScalaBuildServer { this: MillBuildServer =>

  override def buildTargetScalacOptions(p: ScalacOptionsParams)
      : CompletableFuture[ScalacOptionsResult] =
    completableTasks(
      hint = s"buildTarget/scalacOptions ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: JavaModuleApi =>
          m.bspBuildTargetScalacOptions(
            sessionInfo.enableJvmCompileClasspathProvider,
            sessionInfo.clientWantsSemanticDb
          )
      }
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
    completableTasks(
      hint = "buildTarget/scalaMainClasses",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = { case m: JavaModuleApi => m.bspBuildTargetScalaMainClasses }
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
      : CompletableFuture[ScalaTestClassesResult] = ???
//    completableTasks(
//      s"buildTarget/scalaTestClasses ${p}",
//      targetIds = _ => p.getTargets.asScala.toSeq,
//      tasks = {
//        case m: TestModuleApi => m.bspBuildTargetScalaTestClasses
//      }
//    ) {
//      case (ev, state, id, m: TestModuleApi, Some((classpath, testFramework, testClasspath))) =>
//        val (frameworkName, classFingerprint): (String, Seq[(Class[?], Fingerprint)]) =
//          Jvm.withClassLoader(
//            classPath = classpath.map(_.path).toVector,
//            sharedPrefixes = Seq("sbt.testing.")
//          ) { classLoader =>
//            val framework = Framework.framework(testFramework)(classLoader)
//            val discoveredTests = TestRunnerUtils.discoverTests(
//              classLoader,
//              framework,
//              Seq.from(testClasspath.map(_.path))
//            )
//            (framework.name(), discoveredTests)
//          }: @unchecked
//        val classes = Seq.from(classFingerprint.map(classF => classF._1.getName.stripSuffix("$")))
//        new ScalaTestClassesItem(id, classes.asJava).tap { it =>
//          it.setFramework(frameworkName)
//        }
//      case (ev, state, id, _, _) =>
//        // Not a test module, so no test classes
//        new ScalaTestClassesItem(id, Seq.empty[String].asJava)
//    } {
//      new ScalaTestClassesResult(_)
//    }

}
