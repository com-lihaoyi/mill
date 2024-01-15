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
import mill.{Agg, T}
import mill.bsp.worker.Utils.sanitizeUri
import mill.util.Jvm
import mill.scalalib.{JavaModule, ScalaModule, SemanticDbJavaModule, TestModule}
import mill.testrunner.{Framework, TestRunnerUtils}
import sbt.testing.Fingerprint

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._
import scala.util.chaining.scalaUtilChainingOps

class MillScalaBuildServer(base: MillBuildServerBase)
    extends MillBspExtension
    with ScalaBuildServer {

  override def extensionCapabilities: ExtensionCapabilities = ExtensionCapabilities(
    languages = Seq("scala")
  )

  override def buildTargetScalacOptions(p: ScalacOptionsParams)
      : CompletableFuture[ScalacOptionsResult] =
    base.completableTasks(
      hint = s"buildTargetScalacOptions ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: ScalaModule =>
          val classesPathTask = m match {
            case sem: SemanticDbJavaModule if base.enableSemanticDb =>
              sem.bspCompiledClassesAndSemanticDbFiles
            case _ => m.bspCompileClassesPath
          }

          T.task((m.allScalacOptions(), m.bspCompileClasspath(), classesPathTask()))

        case m: JavaModule =>
          val classesPathTask = m match {
            case sem: SemanticDbJavaModule if base.enableSemanticDb =>
              sem.bspCompiledClassesAndSemanticDbFiles
            case _ => m.bspCompileClassesPath
          }
          T.task { (Nil, Nil, classesPathTask()) }
      }
    ) {
      // We ignore all non-JavaModule
      case (
            ev,
            state,
            id,
            m: JavaModule,
            (allScalacOptions, bspCompileClsaspath, classesPathTask)
          ) =>
        val pathResolver = ev.pathsResolver
        new ScalacOptionsItem(
          id,
          allScalacOptions.asJava,
          bspCompileClsaspath.iterator
            .map(_.resolve(pathResolver))
            .map(sanitizeUri).toSeq.asJava,
          sanitizeUri(classesPathTask.resolve(pathResolver))
        )
    } {
      new ScalacOptionsResult(_)
    }

  override def buildTargetScalaMainClasses(p: ScalaMainClassesParams)
      : CompletableFuture[ScalaMainClassesResult] =
    base.completableTasks(
      hint = "buildTargetScalaMainClasses",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = { case m: JavaModule =>
        T.task((m.zincWorker().worker(), m.compile(), m.forkArgs(), m.forkEnv()))
      }
    ) {
      case (ev, state, id, m: JavaModule, (worker, compile, forkArgs, forkEnv)) =>
        // We find all main classes, although we could also find only the configured one
        val mainClasses = worker.discoverMainClasses(compile)
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
    base.completableTasks(
      s"buildTargetScalaTestClasses ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: TestModule =>
          T.task(Some((m.runClasspath(), m.testFramework(), m.compile())))
        case _ =>
          T.task(None)
      }
    ) {
      case (ev, state, id, m: TestModule, Some((classpath, testFramework, compResult))) =>
        val (frameworkName, classFingerprint): (String, Agg[(Class[_], Fingerprint)]) =
          Jvm.inprocess(
            classpath.map(_.path),
            classLoaderOverrideSbtTesting = true,
            isolated = true,
            closeContextClassLoaderWhenDone = false,
            cl => {
              val framework = Framework.framework(testFramework)(cl)
              val discoveredTests = TestRunnerUtils.discoverTests(
                cl,
                framework,
                Agg(compResult.classes.path)
              )
              (framework.name(), discoveredTests)
            }
          )(new mill.api.Ctx.Home { def home = os.home })
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
