package mill.scalanativelib

import java.util.jar.JarFile
import mill._
import mill.api.Result
import mill.define.Discover
import mill.eval.EvaluatorPaths
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.{CrossScalaModule, DepSyntax, Lib, PublishModule, ScalaModule, TestModule}
import mill.testrunner.{TestResult, TestRunner}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.scalanativelib.api._
import mill.util.{TestEvaluator, TestUtil}
import utest._

import scala.jdk.CollectionConverters._

object HelloNativeWorldTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-native-world"

  trait HelloNativeWorldModule
      extends ScalaModule
      with ScalaNativeModule
      with PublishModule
      with Cross.Module3[String, String, ReleaseMode] {
    val (crossScalaVersion, sNativeVersion, mode) = (crossValue, crossValue2, crossValue3)
    def scalaVersion = crossScalaVersion
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("hello.Main")
  }

  val scala213 = "2.13.6"
  val scalaNative04 = "0.4.2"

  object HelloNativeWorld extends TestUtil.BaseModule {
    implicit object ReleaseModeToSegments
        extends Cross.ToSegments[ReleaseMode](v => List(v.toString))

    val matrix = for {
      scala <- Seq("3.2.1", "3.1.3", scala213, "2.12.13", "2.11.12")
      scalaNative <- Seq(scalaNative04, "0.4.9")
      mode <- List(ReleaseMode.Debug, ReleaseMode.ReleaseFast)
      if !(ZincWorkerUtil.isScala3(scala) && scalaNative == scalaNative04)
    } yield (scala, scalaNative, mode)

    object helloNativeWorld extends Cross[RootModule](matrix)
    trait RootModule extends HelloNativeWorldModule {
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
    object buildUTest extends Cross[BuildModuleUtest](matrix)
    trait BuildModuleUtest extends RootModule {
      object test extends ScalaNativeModuleTests with TestModule.Utest {
        override def sources = T.sources { millSourcePath / "src" / "utest" }
        override def ivyDeps = super.ivyDeps() ++ Agg(
          ivy"com.lihaoyi::utest::0.7.6"
        )
      }
    }
    object inherited extends ScalaNativeModule {
      val (scala, scalaNative, _) = matrix.head
      def scalacOptions = Seq("-deprecation")
      def scalaOrganization = "org.example"
      def scalaVersion = scala
      def scalaNativeVersion = scalaNative
      object test extends ScalaNativeModuleTests with TestModule.Utest
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
        val outputFiles = os.walk(outPath).filter(os.isFile).map(_.last).toSet
        val expectedClassfiles = compileClassfiles(scalaVersion, scalaNativeVersion)
        assert(
          outputFiles == expectedClassfiles,
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

    def runTests(testTask: define.NamedTask[(String, Seq[TestResult])])
        : Map[String, Map[String, TestResult]] = {
      val Left(Result.Failure(_, Some(res))) = helloWorldEvaluator(testTask)

      val (doneMsg, testResults) = res
      testResults
        .groupBy(_.fullyQualifiedName)
        .view
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

    test("test") - {
      val cached = false

      testAllMatrix(
        (scala, scalaNative, releaseMode) => checkUtest(scala, scalaNative, releaseMode, cached),
        skipScala = ZincWorkerUtil.isScala3 // Remove this once utest is released for Scala 3
      )
    }
    "testCached" - {
      val cached = true
      testAllMatrix(
        (scala, scalaNative, releaseMode) => checkUtest(scala, scalaNative, releaseMode, cached),
        skipScala = ZincWorkerUtil.isScala3 // Remove this once utest is released for Scala 3
      )
    }

    def checkRun(scalaVersion: String, scalaNativeVersion: String, mode: ReleaseMode): Unit = {
      val task =
        HelloNativeWorld.helloNativeWorld(scalaVersion, scalaNativeVersion, mode).nativeLink
      val Right((_, evalCount)) = helloWorldEvaluator(task)

      val paths = EvaluatorPaths.resolveDestPaths(helloWorldEvaluator.outPath, task)
      val stdout = os.proc(paths.dest / "out").call().out.lines()
      assert(
        stdout.contains("Hello Scala Native"),
        evalCount > 0
      )
    }

    "run" - {
      testAllMatrix((scala, scalaNative, releaseMode) => checkRun(scala, scalaNative, releaseMode))
    }

    def checkInheritedTargets[A](target: ScalaNativeModule => T[A], expected: A) = {
      val Right((mainResult, _)) = helloWorldEvaluator(target(HelloNativeWorld.inherited))
      val Right((testResult, _)) = helloWorldEvaluator(target(HelloNativeWorld.inherited.test))
      assert(mainResult == expected)
      assert(testResult == expected)
    }
    test("test-scalacOptions") {
      checkInheritedTargets(_.scalacOptions, Seq("-deprecation"))
    }
    test("test-scalaOrganization") {
      checkInheritedTargets(_.scalaOrganization, "org.example")
    }
  }

  def compileClassfiles(scalaVersion: String, scalaNativeVersion: String) = {
    val common = Set(
      "ArgsParser$.class",
      "ArgsParser$.nir",
      "ArgsParser.class",
      "Main.class",
      "Main$.class",
      "Main$.nir"
    )

    val scalaVersionSpecific =
      if (ZincWorkerUtil.isScala3(scalaVersion)) Set("ArgsParser.tasty", "Main.tasty")
      else Set(
        "Main$delayedInit$body.class",
        "Main$delayedInit$body.nir"
      )

    val scalaNativeVersionSpecific =
      if (scalaNativeVersion == scalaNative04) Set.empty
      else Set("Main.nir", "ArgsParser.nir")

    common ++ scalaVersionSpecific ++ scalaNativeVersionSpecific
  }

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
        TestUtil.disableInJava9OrAbove("Scala 2.11 tests don't run in Java 9+")(f(
          scala,
          scalaNative,
          releaseMode
        ))
      } else {
        f(scala, scalaNative, releaseMode)
      }
    }
  }
}
