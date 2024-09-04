package mill.scalanativelib

import java.util.jar.JarFile
import mill._
import mill.api.Result
import mill.define.Discover
import mill.eval.EvaluatorPaths
import mill.scalalib.api.ZincWorkerUtil
import mill.scalalib.{DepSyntax, PublishModule, ScalaModule, TestModule}
import mill.testrunner.TestResult
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.scalanativelib.api._
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.util.TestUtil
import utest._

import scala.jdk.CollectionConverters._

object HelloNativeWorldTests extends TestSuite {
  trait HelloNativeWorldModule
      extends ScalaModule
      with ScalaNativeModule
      with PublishModule
      with Cross.Module3[String, String, ReleaseMode] {
    val (crossScalaVersion, sNativeVersion, mode) = (crossValue, crossValue2, crossValue3)
    def scalaVersion = crossScalaVersion
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("hello.Main")
  }

  val scala213 = sys.props.getOrElse("TEST_SCALA_2_13_VERSION_FOR_SCALANATIVE_4_2", ???)
  val scala33 = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)
  val scalaNative05 = sys.props.getOrElse("TEST_SCALANATIVE_0_5_VERSION", ???)
  val utestVersion = sys.props.getOrElse("TEST_UTEST_VERSION", ???)

  object HelloNativeWorld extends TestBaseModule {
    implicit object ReleaseModeToSegments
        extends Cross.ToSegments[ReleaseMode](v => List(v.toString))

    val matrix = for {
      scala <- Seq(scala33, scala213)
      scalaNative <- Seq(scalaNative05)
      mode <- List(ReleaseMode.Debug, ReleaseMode.ReleaseFast)
    } yield (scala, scalaNative, mode)

    object build extends Cross[RootModule](matrix)
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

      object test extends ScalaNativeTests with TestModule.Utest {
        override def sources = T.sources { millSourcePath / "src" / "utest" }
        override def ivyDeps = super.ivyDeps() ++ Agg(
          ivy"com.lihaoyi::utest::$utestVersion"
        )
      }
    }

    object inherited extends ScalaNativeModule {
      val (scala, scalaNative, _) = matrix.head
      def scalacOptions = Seq("-deprecation")
      def scalaOrganization = "org.example"
      def scalaVersion = scala
      def scalaNativeVersion = scalaNative
      object test extends ScalaNativeTests with TestModule.Utest
    }

    override lazy val millDiscover: Discover[HelloNativeWorld.this.type] = Discover[this.type]
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-native-world"

  def tests: Tests = Tests {
    test("compile") {
      def testCompileFromScratch(
          scalaVersion: String,
          scalaNativeVersion: String,
          mode: ReleaseMode
      ): Unit = {
        val eval = UnitTester(HelloNativeWorld, millSourcePath)
        val Right(result) =
          eval(HelloNativeWorld.build(
            scalaVersion,
            scalaNativeVersion,
            mode
          ).compile)

        val outPath = result.value.classes.path
        val outputFiles = os.walk(outPath).filter(os.isFile).map(_.last).toSet
        val expectedClassfiles = compileClassfiles(scalaVersion, scalaNativeVersion)
        assert(
          outputFiles == expectedClassfiles,
          result.evalCount > 0
        )

        // don't recompile if nothing changed
        val Right(result2) =
          eval(HelloNativeWorld.build(
            scalaVersion,
            scalaNativeVersion,
            mode
          ).compile)
        assert(result2.evalCount == 0)
      }

      testAllMatrix((scala, scalaNative, releaseMode) =>
        testCompileFromScratch(scala, scalaNative, releaseMode)
      )
    }

    test("jar") {
      test("containsNirs") {
        val eval = UnitTester(HelloNativeWorld, millSourcePath)
        val Right(result) =
          eval(HelloNativeWorld.build(
            scala213,
            scalaNative05,
            ReleaseMode.Debug
          ).jar)
        val jar = result.value.path
        val entries = new JarFile(jar.toIO).entries().asScala.map(_.getName)
        assert(entries.contains("hello/Main$.nir"))
      }
    }
    test("publish") {
      def testArtifactId(
          scalaVersion: String,
          scalaNativeVersion: String,
          mode: ReleaseMode,
          artifactId: String
      ): Unit = {
        val eval = UnitTester(HelloNativeWorld, millSourcePath)
        val Right(result) = eval(
          HelloNativeWorld.build(
            scalaVersion,
            scalaNativeVersion,
            mode: ReleaseMode
          ).artifactMetadata
        )
        assert(result.value.id == artifactId)
      }
    }

    def runTests(testTask: define.NamedTask[(String, Seq[TestResult])])
        : Map[String, Map[String, TestResult]] = {
      val eval = UnitTester(HelloNativeWorld, millSourcePath)
      val Left(Result.Failure(_, Some(res))) = eval(testTask)

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
        if (!cached) HelloNativeWorld.build(scalaVersion, scalaNativeVersion, mode).test.test()
        else HelloNativeWorld.build(scalaVersion, scalaNativeVersion, mode).test.testCached
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

      testAllMatrix((scala, scalaNative, releaseMode) =>
        checkUtest(scala, scalaNative, releaseMode, cached)
      )
    }
    test("testCached") {
      val cached = true
      testAllMatrix((scala, scalaNative, releaseMode) =>
        checkUtest(scala, scalaNative, releaseMode, cached)
      )
    }

    def checkRun(scalaVersion: String, scalaNativeVersion: String, mode: ReleaseMode): Unit = {
      val eval = UnitTester(HelloNativeWorld, millSourcePath)
      val task =
        HelloNativeWorld.build(scalaVersion, scalaNativeVersion, mode).nativeLink
      val Right(result) = eval(task)

      val paths = EvaluatorPaths.resolveDestPaths(eval.outPath, task)
      val stdout = os.proc(paths.dest / "out").call().out.lines()
      assert(
        stdout.contains("Hello Scala Native"),
        result.evalCount > 0
      )
    }

    test("run") {
      testAllMatrix((scala, scalaNative, releaseMode) => checkRun(scala, scalaNative, releaseMode))
    }

    def checkInheritedTargets[A](target: ScalaNativeModule => T[A], expected: A) = {
      val eval = UnitTester(HelloNativeWorld, millSourcePath)
      val Right(mainResult) = eval(target(HelloNativeWorld.inherited))
      val Right(testResult) = eval(target(HelloNativeWorld.inherited.test))
      assert(mainResult.value == expected)
      assert(testResult.value == expected)
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

    val scalaNativeVersionSpecific = Set("Main.nir", "ArgsParser.nir")

    common ++ scalaVersionSpecific ++ scalaNativeVersionSpecific
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
