package mill.bsp.worker

import ch.epfl.scala.bsp4j.{ScalaBuildServer, ScalaMainClass, ScalaMainClassesItem, ScalaMainClassesParams, ScalaMainClassesResult, ScalaTestClassesItem, ScalaTestClassesParams, ScalaTestClassesResult, ScalacOptionsItem, ScalacOptionsParams, ScalacOptionsResult}
import mill.api.internal
import mill.modules.Jvm
import mill.{Agg, T}
import mill.scalalib.{JavaModule, ScalaModule, SemanticDbJavaModule, TestModule}
import mill.testrunner.TestRunner
import sbt.testing.Fingerprint

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._
import scala.util.chaining.scalaUtilChainingOps

@internal
trait MillScalaBuildServer extends ScalaBuildServer { this: MillBuildServer =>

  override def buildTargetScalacOptions(p: ScalacOptionsParams)
      : CompletableFuture[ScalacOptionsResult] =
    completable(hint = s"buildTargetScalacOptions ${p}") { state =>
      targetTasks(
        state,
        targetIds = p.getTargets.asScala.toSeq,
        agg = (items: Seq[ScalacOptionsItem]) => new ScalacOptionsResult(items.asJava)
      ) {
        case (id, m: JavaModule) =>
          val optionsTask = m match {
            case sm: ScalaModule => sm.allScalacOptions
            case _ => T.task { Seq.empty[String] }
          }
          val classesPathTask = m match {
            case sem: SemanticDbJavaModule if clientWantsSemanticDb =>
              sem.bspCompiledClassesAndSemanticDbFiles
            case _ => m.bspCompileClassesPath
          }

          val pathResolver = state.evaluator.pathsResolver
          T.task {
            new ScalacOptionsItem(
              id,
              optionsTask().asJava,
              m.bspCompileClasspath()
                .iterator
                .map(_.resolve(pathResolver))
                .map(sanitizeUri.apply).toSeq.asJava,
              sanitizeUri(classesPathTask().resolve(pathResolver))
            )
          }
      }
    }

  override def buildTargetScalaMainClasses(p: ScalaMainClassesParams)
      : CompletableFuture[ScalaMainClassesResult] =
    completableTasks(
      hint = "buildTargetScalaMainClasses",
      targetIds = p.getTargets.asScala.toSeq,
      agg = (items: Seq[ScalaMainClassesItem]) => new ScalaMainClassesResult(items.asJava)
    ) {
      case (id, m: JavaModule) =>
        T.task {
          // We find all main classes, although we could also find only the configured one
          val mainClasses = m.zincWorker.worker().discoverMainClasses(m.compile())
          // val mainMain = m.mainClass().orElse(if(mainClasses.size == 1) mainClasses.headOption else None)
          val jvmOpts = m.forkArgs()
          val envs = m.forkEnv()
          val items = mainClasses.map(mc =>
            new ScalaMainClass(mc, Seq().asJava, jvmOpts.asJava).tap {
              _.setEnvironmentVariables(envs.map(e => s"${e._1}=${e._2}").toSeq.asJava)
            }
          )
          new ScalaMainClassesItem(id, items.asJava)
        }
      case (id, _) => T.task {
          // no Java module, so no main classes
          new ScalaMainClassesItem(id, Seq.empty[ScalaMainClass].asJava)
        }

    }

  override def buildTargetScalaTestClasses(p: ScalaTestClassesParams)
      : CompletableFuture[ScalaTestClassesResult] =
    completableTasks(
      s"buildTargetScalaTestClasses ${p}",
      targetIds = p.getTargets.asScala.toSeq,
      agg = (items: Seq[ScalaTestClassesItem]) => new ScalaTestClassesResult(items.asJava)
    ) {
      case (id, m: TestModule) => T.task {
          val classpath = m.runClasspath()
          val testFramework = m.testFramework()
          val compResult = m.compile()

          val (frameworkName, classFingerprint): (String, Agg[(Class[_], Fingerprint)]) =
            Jvm.inprocess(
              classpath.map(_.path),
              classLoaderOverrideSbtTesting = true,
              isolated = true,
              closeContextClassLoaderWhenDone = false,
              cl => {
                val framework = TestRunner.framework(testFramework)(cl)
                val discoveredTests = TestRunner.discoverTests(
                  cl,
                  framework,
                  Agg(compResult.classes.path)
                )
                (framework.name(), discoveredTests)
              }
            )
          val classes = Seq.from(classFingerprint.map(classF => classF._1.getName.stripSuffix("$")))
          new ScalaTestClassesItem(id, classes.asJava, frameworkName)
        }
      case (id, _) => T.task {
          // Not a test module, so no test classes
          new ScalaTestClassesItem(id, Seq.empty[String].asJava)
        }
    }

}
