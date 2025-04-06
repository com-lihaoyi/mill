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
import mill.Task
import mill.bsp.worker.Utils.sanitizeUri
import mill.util.Jvm
import mill.scalalib.{JavaModule, ScalaModule, TestModule, UnresolvedPath}
import mill.testrunner.{Framework, TestRunnerUtils}
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
        case m: JavaModule =>
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
            m: JavaModule,
            (allScalacOptions, compileClasspath, classesPathTask)
          ) =>
        new ScalacOptionsItem(
          id,
          allScalacOptions.asJava,
          compileClasspath.iterator
            .map(_.resolve(ev.outPath))
            .map(sanitizeUri).toSeq.asJava,
          sanitizeUri(classesPathTask.resolve(ev.outPath))
        )
      case _ => ???
    } {
      new ScalacOptionsResult(_)
    }

  override def buildTargetScalaMainClasses(p: ScalaMainClassesParams)
      : CompletableFuture[ScalaMainClassesResult] =
    completableTasks(
      hint = "buildTarget/scalaMainClasses",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = { case m: JavaModule => m.bspBuildTargetScalaMainClasses }
    ) {
      case (ev, state, id, m: JavaModule, (classes, forkArgs, forkEnv)) =>
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
    completableTasks(
      s"buildTarget/scalaTestClasses ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: TestModule => m.bspBuildTargetScalaTestClasses
      }
    ) {
      case (ev, state, id, m: TestModule, Some((classpath, testFramework, testClasspath))) =>
        val (frameworkName, classFingerprint): (String, Seq[(Class[?], Fingerprint)]) =
          Jvm.withClassLoader(
            classPath = classpath.map(_.path).toVector,
            sharedPrefixes = Seq("sbt.testing.")
          ) { classLoader =>
            val framework = Framework.framework(testFramework)(classLoader)
            val discoveredTests = TestRunnerUtils.discoverTests(
              classLoader,
              framework,
              Seq.from(testClasspath.map(_.path))
            )
            (framework.name(), discoveredTests)
          }: @unchecked
        val classes = Seq.from(classFingerprint.map(classF => classF._1.getName.stripSuffix("$")))
        new ScalaTestClassesItem(id, classes.asJava).tap { it =>
          it.setFramework(frameworkName)
        }
      case (ev, state, id, _, _) =>
        // Not a test module, so no test classes
        new ScalaTestClassesItem(id, Seq.empty[String].asJava)
    } {
      new ScalaTestClassesResult(_)
    }

}
