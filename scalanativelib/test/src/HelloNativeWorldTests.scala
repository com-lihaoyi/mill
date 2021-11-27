package mill.scalanativelib

import java.util.jar.JarFile
import mill._
import mill.define.Discover
import mill.eval.{EvaluatorPaths, Result}
import mill.scalalib.{CrossScalaModule, DepSyntax, Lib, PublishModule, TestModule, TestRunner}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.scalanativelib.api._
import mill.util.{TestEvaluator, TestUtil}
import utest._

import scala.collection.JavaConverters._

object HelloNativeWorldTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-native-world"

  trait HelloNativeWorldModule extends CrossScalaModule with ScalaNativeModule with PublishModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("hello.Main")
  }

  val scala213 = "2.13.6"
  val scalaNative04 = "0.4.0"

  object HelloNativeWorld extends TestUtil.BaseModule {
    val matrix = for {
      scala <- Seq(scala213, "2.12.13", "2.11.12")
      scalaNative <- Seq(scalaNative04)
      mode <- List(ReleaseMode.Debug, ReleaseMode.ReleaseFast)
    } yield (scala, scalaNative, mode)

    object helloNativeWorld extends Cross[BuildModule](matrix: _*)
    class BuildModule(val crossScalaVersion: String, sNativeVersion: String, mode: ReleaseMode)
        extends HelloNativeWorldModule {
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
    trait UtestTestModule extends ScalaNativeModule with TestModule.Utest {
      override def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"com.lihaoyi::utest::0.7.6"
      )
    }
    object buildUTest extends Cross[BuildModuleUtest](matrix: _*)
    class BuildModuleUtest(crossScalaVersion: String, sNativeVersion: String, mode: ReleaseMode)
        extends BuildModule(crossScalaVersion, sNativeVersion, mode) {
      object test extends super.Tests with UtestTestModule {
        override def sources = T.sources { millSourcePath / "src" / "utest" }
      }
    }

    object buildNoTests extends Cross[BuildModuleNoTests](matrix: _*)
    class BuildModuleNoTests(crossScalaVersion: String, sNativeVersion: String, mode: ReleaseMode)
        extends BuildModule(crossScalaVersion, sNativeVersion, mode) {
      object test extends super.Tests with UtestTestModule {
        override def sources = T.sources { millSourcePath / "src" / "no-tests" }
      }
    }
    override lazy val millDiscover: Discover[HelloNativeWorld.this.type] = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalanativelib" / "test" / "resources" / "hello-native-world"

  val helloWorldEvaluator = TestEvaluator.static(HelloNativeWorld)

  val mainObject = helloWorldEvaluator.outPath / "src" / "Main.scala"

  def tests: Tests = Tests {
    prepareWorkspace()
    "compile" - {
      def testCompileFromScratch(
          scalaVersion: String,
          scalaNativeVersion: String,
          mode: ReleaseMode
      ): Unit = {
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloNativeWorld.helloNativeWorld(
            scalaVersion,
            scalaNativeVersion,
            mode
          ).compile)

        val outPath = result.classes.path
        val outputFiles = os.walk(outPath).filter(os.isFile)
        val expectedClassfiles = compileClassfiles(outPath / "hello")
        assert(
          outputFiles.toSet == expectedClassfiles,
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) =
          helloWorldEvaluator(HelloNativeWorld.helloNativeWorld(
            scalaVersion,
            scalaNativeVersion,
            mode
          ).compile)
        assert(unchangedEvalCount == 0)
      }

      testAllMatrix((scala, scalaNative, releaseMode) =>
        testCompileFromScratch(scala, scalaNative, releaseMode)
      )
    }

    "jar" - {
      "containsNirs" - {
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloNativeWorld.helloNativeWorld(
            scala213,
            scalaNative04,
            ReleaseMode.Debug
          ).jar)
        val jar = result.path
        val entries = new JarFile(jar.toIO).entries().asScala.map(_.getName)
        assert(entries.contains("hello/Main$.nir"))
      }
    }
    "publish" - {
      def testArtifactId(
          scalaVersion: String,
          scalaNativeVersion: String,
          mode: ReleaseMode,
          artifactId: String
      ): Unit = {
        val Right((result, evalCount)) = helloWorldEvaluator(
          HelloNativeWorld.helloNativeWorld(
            scalaVersion,
            scalaNativeVersion,
            mode: ReleaseMode
          ).artifactMetadata
        )
        assert(result.id == artifactId)
      }
      "artifactId_040" - testArtifactId(
        scala213,
        scalaNative04,
        ReleaseMode.Debug,
        "hello-native-world_native0.4_2.13"
      )
    }

    def runTests(testTask: define.NamedTask[(String, Seq[TestRunner.Result])])
        : Map[String, Map[String, TestRunner.Result]] = {
      val Left(Result.Failure(_, Some(res))) = helloWorldEvaluator(testTask)

      val (doneMsg, testResults) = res
      testResults
        .groupBy(_.fullyQualifiedName)
        .mapValues(_.map(e => e.selector -> e).toMap)
        .toMap
    }

    def checkUtest(
        scalaVersion: String,
        scalaNativeVersion: String,
        mode: ReleaseMode,
        cached: Boolean
    ) = {
      val resultMap = runTests(
        if (!cached) HelloNativeWorld.buildUTest(scalaVersion, scalaNativeVersion, mode).test.test()
        else HelloNativeWorld.buildUTest(scalaVersion, scalaNativeVersion, mode).test.testCached
      )

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

    def checkNoTests(
        scalaVersion: String,
        scalaNativeVersion: String,
        mode: ReleaseMode,
        cached: Boolean
    ) = {
      val Right(((message, results), _)) = helloWorldEvaluator(
        if (!cached)
          HelloNativeWorld.buildNoTests(scalaVersion, scalaNativeVersion, mode).test.test()
        else HelloNativeWorld.buildNoTests(scalaVersion, scalaNativeVersion, mode).test.testCached
      )

      assert(
        results.size == 0,
        message == "\n"
      )
    }

    "test" - {
      val cached = false

      testAllMatrix((scala, scalaNative, releaseMode) =>
        checkNoTests(scala, scalaNative, releaseMode, cached)
      )
      testAllMatrix((scala, scalaNative, releaseMode) =>
        checkUtest(scala, scalaNative, releaseMode, cached)
      )
    }
    "testCached" - {
      val cached = true
      testAllMatrix((scala, scalaNative, releaseMode) =>
        checkNoTests(scala, scalaNative, releaseMode, cached)
      )
      testAllMatrix((scala, scalaNative, releaseMode) =>
        checkUtest(scala, scalaNative, releaseMode, cached)
      )
    }

    def checkRun(scalaVersion: String, scalaNativeVersion: String, mode: ReleaseMode): Unit = {
      val task =
        HelloNativeWorld.helloNativeWorld(scalaVersion, scalaNativeVersion, mode).nativeLink
      val Right((_, evalCount)) = helloWorldEvaluator(task)

      val paths = EvaluatorPaths.resolveDestPaths(helloWorldEvaluator.outPath, task)
      val stdout = os.proc(paths.dest / "out").call().out.lines
      assert(
        stdout.contains("Hello Scala Native"),
        evalCount > 0
      )
    }

    "run" - {
      testAllMatrix((scala, scalaNative, releaseMode) => checkRun(scala, scalaNative, releaseMode))
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

  def testAllMatrix(
      f: (String, String, ReleaseMode) => Unit,
      skipScala: String => Boolean = _ => false,
      skipScalaNative: String => Boolean = _ => false,
      skipReleaseMode: ReleaseMode => Boolean = _ => false
  ): Unit = {
    for {
      (scala, scalaNative, releaseMode) <- HelloNativeWorld.matrix
      if !skipScala(scala)
      if !skipScalaNative(scalaNative)
      if !skipReleaseMode(releaseMode)
    } {
      if (scala.startsWith("2.11.")) {
        TestUtil.disableInJava9OrAbove(f(scala, scalaNative, releaseMode))
      } else {
        f(scala, scalaNative, releaseMode)
      }
    }
  }
}
