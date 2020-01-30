package mill.scalanativelib

import java.util.jar.JarFile

import mill._
import mill.define.Discover
import mill.eval.{Evaluator, Result}
import mill.scalalib.{CrossScalaModule, DepSyntax, Lib, PublishModule, TestRunner}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.util.{TestEvaluator, TestUtil}
import utest._


import scala.collection.JavaConverters._
import mill.scalanativelib.api._

object HelloNativeWorldTests extends TestSuite {
  val workspacePath =  TestUtil.getOutPathStatic() / "hello-native-world"

  trait HelloNativeWorldModule extends CrossScalaModule with ScalaNativeModule with PublishModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("hello.Main")
  }

  val scala211 = "2.11.12"
  val scalaNative03 = "0.3.9"
  val scalaNative04 = "0.4.0-M2"

  object HelloNativeWorld extends TestUtil.BaseModule {
    val matrix = for {
      scala <- Seq(scala211)
      scalaNative <- Seq(scalaNative03, scalaNative04)
      mode <- List(ReleaseMode.Debug, ReleaseMode.Release)
    } yield (scala, scalaNative, mode)

    object helloNativeWorld extends Cross[BuildModule](matrix:_*)
    class BuildModule(val crossScalaVersion: String, sNativeVersion: String, mode: ReleaseMode) extends HelloNativeWorldModule {
      override def artifactName = "hello-native-world"
      def scalaNativeVersion = sNativeVersion
      def releaseMode = T { mode }
      def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "hello native world ready for real world publishing",
        url = "https://github.com/lihaoyi/hello-world-publish",
        licenses = Seq(License.Common.Apache2),
        versionControl = VersionControl.github("lihaoyi", "hello-world-publish"),
        developers =
          Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
    }

    object buildUTest extends Cross[BuildModuleUtest](matrix:_*)
    class BuildModuleUtest(crossScalaVersion: String, sNativeVersion: String, mode: ReleaseMode)
      extends BuildModule(crossScalaVersion, sNativeVersion, mode) {
      object test extends super.Tests {
        override def sources = T.sources{ millSourcePath / 'src / 'utest }
        def testFrameworks = Seq("utest.runner.Framework")
        override def ivyDeps = Agg(
          ivy"com.lihaoyi::utest::0.6.4"
        )
      }
    }

    object buildScalaTest extends Cross[BuildModuleScalaTest](matrix:_*)
    class BuildModuleScalaTest(crossScalaVersion: String, sNativeVersion: String, mode: ReleaseMode)
      extends BuildModule(crossScalaVersion, sNativeVersion, mode) {
      object test extends super.Tests {
        override def sources = T.sources{ millSourcePath / 'src / 'scalatest }
        def testFrameworks = Seq("org.scalatest.tools.Framework")
        override def ivyDeps = Agg(
          ivy"org.scalatest::scalatest-flatspec::3.2.0-M2",
          ivy"org.scalatest::scalatest-shouldmatchers::3.2.0-M2"
        )
      }
    }
    override lazy val millDiscover: Discover[HelloNativeWorld.this.type] = Discover[this.type]
  }

  val millSourcePath = os.pwd / 'scalanativelib / 'test / 'resources / "hello-native-world"

  val helloWorldEvaluator = TestEvaluator.static(HelloNativeWorld)

  val mainObject = helloWorldEvaluator.outPath / 'src / "Main.scala"

  def tests: Tests = Tests {
    prepareWorkspace()
    'compile - {
      def testCompileFromScratch(scalaVersion: String,
                                 scalaNativeVersion: String,
                                 mode: ReleaseMode): Unit = {
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloNativeWorld.helloNativeWorld(scalaVersion, scalaNativeVersion, mode).compile)

        val outPath = result.classes.path
        val outputFiles = os.walk(outPath).filter(os.isFile)
        val expectedClassfiles = compileClassfiles(outPath / 'hello)
        assert(
          outputFiles.toSet == expectedClassfiles,
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) =
          helloWorldEvaluator(HelloNativeWorld.helloNativeWorld(scalaVersion, scalaNativeVersion, mode).compile)
        assert(unchangedEvalCount == 0)
      }

      'fromScratch_21112_039 - testCompileFromScratch(scala211, scalaNative03, ReleaseMode.Debug)
      'fromScratch_21112_040M2 - testCompileFromScratch(scala211, scalaNative04, ReleaseMode.Debug)
    }

    'jar - {
      'containsNirs - {
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloNativeWorld.helloNativeWorld(scala211, scalaNative04, ReleaseMode.Debug).jar)
        val jar = result.path
        val entries = new JarFile(jar.toIO).entries().asScala.map(_.getName)
        assert(entries.contains("hello/Main$.nir"))
      }
    }
    'publish - {
      def testArtifactId(scalaVersion: String,
                         scalaNativeVersion: String,
                         mode: ReleaseMode,
                         artifactId: String): Unit = {
        val Right((result, evalCount)) = helloWorldEvaluator(
          HelloNativeWorld.helloNativeWorld(scalaVersion, scalaNativeVersion, mode: ReleaseMode).artifactMetadata)
        assert(result.id == artifactId)
      }
      'artifactId_039 - testArtifactId(scala211, scalaNative03, ReleaseMode.Debug, "hello-native-world_native0.3_2.11")
      'artifactId_040M2 - testArtifactId(scala211, scalaNative04, ReleaseMode.Debug, "hello-native-world_native0.4.0-M2_2.11")
    }
    'test - {
      def runTests(testTask: define.Command[(String, Seq[TestRunner.Result])]): Map[String, Map[String, TestRunner.Result]] = {
        val Left(Result.Failure(_, Some(res))) = helloWorldEvaluator(testTask)

        val (doneMsg, testResults) = res
        testResults
          .groupBy(_.fullyQualifiedName)
          .mapValues(_.map(e => e.selector -> e).toMap)
      }

      def checkUtest(scalaVersion: String, scalaNativeVersion: String, mode: ReleaseMode) = {
        val resultMap = runTests(HelloNativeWorld.buildUTest(scalaVersion, scalaNativeVersion, mode).test.test())

        val mainTests = resultMap("hellotest.MainTests")
        val argParserTests = resultMap("hellotest.ArgsParserTests")

        assert(
          mainTests.size == 2,
          mainTests("hellotest.MainTests.vmName.containNative").status == "Success",
          mainTests("hellotest.MainTests.vmName.containScala").status == "Success",

          argParserTests.size == 2,
          argParserTests("hellotest.ArgsParserTests.one").status == "Success",
          argParserTests("hellotest.ArgsParserTests.two").status == "Failure"
        )
      }

      def checkScalaTest(scalaVersion: String, scalaNativeVersion: String, mode: ReleaseMode) = {
        val resultMap = runTests(HelloNativeWorld.buildScalaTest(scalaVersion, scalaNativeVersion, mode).test.test())

        val mainSpec = resultMap("hellotest.MainSpec")
        val argParserSpec = resultMap("hellotest.ArgsParserSpec")

        assert(
          mainSpec.size == 2,
          mainSpec("vmName should contain Native").status == "Success",
          mainSpec("vmName should contain Scala").status == "Success",

          argParserSpec.size == 2,
          argParserSpec("parse should one").status == "Success",
          argParserSpec("parse should two").status == "Failure"
        )
      }

      'utest_21112_039_debug - (checkUtest(scala211, scalaNative03, ReleaseMode.Debug))
      'utest_21112_039_release - (checkUtest(scala211, scalaNative03, ReleaseMode.Release))
//      No utest artifact for Scala Native 0.4.0-M2 published yet
//      'utest_21112_040M2_debug - (checkUtest(scala211, scalaNative04, ReleaseMode.Debug))
//      'utest_21112_040M2_release - (checkUtest(scala211, scalaNative04, ReleaseMode.Release))

//      Scalatest dropped Scala Native 0.3 support
      'scalaTest_21112_040M2_debug - (checkScalaTest(scala211, scalaNative04, ReleaseMode.Debug))
//      Disabled since it consumes too much memory      
//      'scalaTest_21112_040M2_release - (checkScalaTest(scala211, scalaNative04, ReleaseMode.Release))
    }

    def checkRun(scalaVersion: String, scalaNativeVersion: String, mode: ReleaseMode): Unit = {
      val task = HelloNativeWorld.helloNativeWorld(scalaVersion, scalaNativeVersion, mode).nativeLink
      val Right((_, evalCount)) = helloWorldEvaluator(task)

      val paths = Evaluator.resolveDestPaths(
        helloWorldEvaluator.outPath,
        task.ctx.segments
      )
      val stdout = ammonite.ops.%%("./out")(paths.out / 'dest).out.lines
      assert(stdout.contains("Hello Scala Native"))
    }

    'run - {
      'run_21112_039_debug  - (checkRun(scala211, scalaNative03, ReleaseMode.Debug))
      'run_21112_039_release  - (checkRun(scala211, scalaNative03, ReleaseMode.Release))
      'run_21112_040M2_debug  - (checkRun(scala211, scalaNative04, ReleaseMode.Debug))
      'run_21112_040M2_release  - (checkRun(scala211, scalaNative04, ReleaseMode.Release))
    }
  }

  def compileClassfiles(parentDir: os.Path) = Set(
    parentDir / "ArgsParser$.class",
    parentDir / "ArgsParser$.nir",
    parentDir / "ArgsParser.class",
    parentDir / "Main.class",
    parentDir / "Main$.class",
    parentDir / "Main$delayedInit$body.class",
    parentDir / "Main$.nir",
    parentDir / "Main$delayedInit$body.nir"
  )

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }
}
