package mill.scalajslib

import java.io.{FileReader, StringWriter}
import java.util.jar.JarFile
import javax.script.{ScriptContext, ScriptEngineManager}

import ammonite.ops._
import mill._
import mill.define.Cross
import mill.discover.Discovered
import mill.scalalib.PublishModule
import mill.scalalib.publish.{Developer, License, PomSettings, SCM}
import mill.util.{TestEvaluator, TestUtil}
import utest._

import scala.collection.JavaConverters._

trait HelloJSWorldModule extends TestUtil.BaseModule with ScalaJSModule with PublishModule {
  override def basePath = HelloJSWorldTests.workspacePath
  override def mainClass = Some("Main")
}

object HelloJSWorld extends TestUtil.BaseModule {
  val build = for {
    scalaJS <- Cross("0.6.20", "0.6.21", "1.0.0-M2")
    scala <- Cross("2.11.8", "2.12.3", "2.12.4")
  } yield
    new HelloJSWorldModule {
      def scalaVersion = scala
      def scalaJSVersion = scalaJS
      def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "hello js world ready for real world publishing",
        url = "https://github.com/lihaoyi/hello-world-publish",
        licenses = Seq(
          License("Apache License, Version 2.0",
                  "http://www.apache.org/licenses/LICENSE-2.0")),
        scm = SCM(
          "https://github.com/lihaoyi/hello-world-publish",
          "scm:git:https://github.com/lihaoyi/hello-world-publish"
        ),
        developers =
          Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
    }
}

object HelloJSWorldTests extends TestSuite {

  val srcPath = pwd / 'scalajslib / 'src / 'test / 'resource / "hello-js-world"
  val workspacePath = pwd / 'target / 'workspace / "hello-js-world"
  val outputPath = workspacePath / 'out
  val mainObject = workspacePath / 'src / 'main / 'scala / "Main.scala"

  val helloWorldEvaluator = new TestEvaluator(
    Discovered.mapping(HelloJSWorld),
    workspacePath,
    srcPath
  )

  class Console {
    val out = new StringWriter()
    def log(s: String): Unit = out.append(s)
  }

  def runJS(path: Path): String = {
    val engineManager = new ScriptEngineManager
    val engine = engineManager.getEngineByName("nashorn")
    val console = new Console
    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("console", console)
    engine.eval(new FileReader(path.toIO))
    console.out.toString
  }

  def tests: Tests = Tests {
    prepareWorkspace()
    'compile - {
      def testCompileFromScratch(scalaVersion: String,
                          scalaJSVersion: String): Unit = {
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloJSWorld.build(scalaVersion, scalaJSVersion).compile)

        val outPath = result.classes.path
        val outputFiles = ls.rec(outPath)
        val expectedClassfiles = compileClassfiles(outPath)
        assert(
          outputFiles.toSet == expectedClassfiles,
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) =
          helloWorldEvaluator(HelloJSWorld.build(scalaVersion, scalaJSVersion).compile)
        assert(unchangedEvalCount == 0)
      }

      'fromScratch_2124_0621 - testCompileFromScratch("2.12.4", "0.6.21")
      'fromScratch_2123_0621 - testCompileFromScratch("2.12.3", "0.6.21")
      'fromScratch_2118_0621 - testCompileFromScratch("2.11.8", "0.6.21")
      'fromScratch_2124_100M2 - testCompileFromScratch("2.11.8", "1.0.0-M2")
    }

    def testRun(scalaVersion: String,
                scalaJSVersion: String,
                mode: OptimizeMode): Unit = {
      val task = mode match {
        case FullOpt => HelloJSWorld.build(scalaVersion, scalaJSVersion).fullOpt
        case FastOpt => HelloJSWorld.build(scalaVersion, scalaJSVersion).fastOpt
      }
      val Right((result, evalCount)) = helloWorldEvaluator(task)
      val output = runJS(result.path)
      assert(output == "Hello Scala.js")
    }

    'fullOpt - {
      'run_2124_0621 - testRun("2.12.4", "0.6.21", FullOpt)
      'run_2123_0621 - testRun("2.12.3", "0.6.21", FullOpt)
      'run_2118_0621 - testRun("2.11.8", "0.6.21", FullOpt)
      'run_2124_100M2 - testRun("2.11.8", "1.0.0-M2", FullOpt)
    }
    'fastOpt - {
      'run_2124_0621 - testRun("2.12.4", "0.6.21", FastOpt)
      'run_2123_0621 - testRun("2.12.3", "0.6.21", FastOpt)
      'run_2118_0621 - testRun("2.11.8", "0.6.21", FastOpt)
      'run_2124_100M2 - testRun("2.11.8", "1.0.0-M2", FastOpt)
    }
    'jar - {
      'containsSJSIRs - {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloJSWorld.build("2.12.4", "0.6.21").jar)
        val jar = result.path
        val entries = new JarFile(jar.toIO).entries().asScala.map(_.getName)
        assert(entries.contains("Main$.sjsir"))
      }
    }
    'publish - {
      def testArtifactId(scalaVersion: String,
                         scalaJSVersion: String,
                         artifactId: String): Unit = {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloJSWorld.build(scalaVersion, scalaJSVersion).artifact)
        assert(result.id == artifactId)
      }
      'artifactId_0621 - testArtifactId("2.12.4", "0.6.21", "hello-js-world_sjs0.6_2.12")
      'artifactId_0621 - testArtifactId("2.12.4", "1.0.0-M2", "hello-js-world_sjs1.0.0-M2_2.12")
    }
  }

  def compileClassfiles(parentDir: Path) = Set(
    parentDir / "Main.class",
    parentDir / "Main$.class",
    parentDir / "Main$delayedInit$body.class",
    parentDir / "Main$.sjsir",
    parentDir / "Main$delayedInit$body.sjsir"
  )

  def prepareWorkspace(): Unit = {
    rm(workspacePath)
    mkdir(workspacePath / up)
    cp(srcPath, workspacePath)
  }

}
