package mill.scalanativelib

import mill._
import mill.api.Discover
import mill.api.ExecutionPaths
import mill.javalib.api.JvmWorkerUtil
import mill.scalalib.{PublishModule, ScalaModule, TestModule}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.scalanativelib.api._
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest._

import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

object CompileRunTests extends TestSuite {
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
  val testUtestVersion = sys.props.getOrElse("TEST_UTEST_VERSION", ???)

  object HelloNativeWorld extends TestRootModule {
    implicit object ReleaseModeToSegments
        extends Cross.ToSegments[ReleaseMode](v => List(v.toString))

    val matrix = Seq(
      (scala33, scalaNative05, ReleaseMode.Debug),
      (scala213, scalaNative05, ReleaseMode.ReleaseFast)
    )

    object build extends Cross[RootModule](matrix)
    trait RootModule extends HelloNativeWorldModule {
      override def artifactName = "hello-native-world"
      def scalaNativeVersion = sNativeVersion
      def releaseMode = Task { mode }
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
        override def sources = Task.Sources("src/utest")
        override def utestVersion: T[String] = testUtestVersion
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

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-native-world"

  def tests: Tests = Tests {
    test("compile") {
      def testCompileFromScratch(
          scalaVersion: String,
          scalaNativeVersion: String,
          mode: ReleaseMode
      ): Unit = UnitTester(HelloNativeWorld, millSourcePath).scoped { eval =>
        val Right(result) =
          eval(HelloNativeWorld.build(
            scalaVersion,
            scalaNativeVersion,
            mode
          ).compile): @unchecked

        val outPath = result.value.classes.path
        val outputFiles = os.walk(outPath).filter(os.isFile).map(_.last).toSet
        val expectedClassfiles = compileClassfiles(scalaVersion)
        assertAll(
          outputFiles == expectedClassfiles,
          result.evalCount > 0
        )

        // don't recompile if nothing changed
        val Right(result2) =
          eval(HelloNativeWorld.build(
            scalaVersion,
            scalaNativeVersion,
            mode
          ).compile): @unchecked
        assert(result2.evalCount == 0)
      }

      testAllMatrix((scala, scalaNative, releaseMode) =>
        testCompileFromScratch(scala, scalaNative, releaseMode)
      )
    }

    test("jar") {
      test("containsNirs") - UnitTester(HelloNativeWorld, millSourcePath).scoped { eval =>
        val Right(result) =
          eval(HelloNativeWorld.build(
            scala213,
            scalaNative05,
            ReleaseMode.ReleaseFast
          ).jar): @unchecked
        val jar = result.value.path
        val entries = new JarFile(jar.toIO).entries().asScala.map(_.getName)
        assert(entries.contains("hello/Main$.nir"))
      }
    }

    def checkRun(scalaVersion: String, scalaNativeVersion: String, mode: ReleaseMode): Unit =
      UnitTester(HelloNativeWorld, millSourcePath).scoped { eval =>
        val task =
          HelloNativeWorld.build(scalaVersion, scalaNativeVersion, mode).nativeLink
        val Right(result) = eval(task): @unchecked

        val paths = ExecutionPaths.resolve(eval.outPath, task)
        val stdout = os.proc(paths.dest / "out").call().out.lines()
        assertAll(
          stdout.contains("Hello Scala Native"),
          result.evalCount > 0
        )
      }

    test("run") {
      testAllMatrix((scala, scalaNative, releaseMode) => checkRun(scala, scalaNative, releaseMode))
    }

  }

  def compileClassfiles(scalaVersion: String) = {
    val common = Set(
      "ArgsParser$.class",
      "ArgsParser$.nir",
      "ArgsParser.class",
      "Main.class",
      "Main$.class",
      "Main$.nir"
    )

    val scalaVersionSpecific =
      if (JvmWorkerUtil.isScala3(scalaVersion)) Set("ArgsParser.tasty", "Main.tasty")
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
      f(scala, scalaNative, releaseMode)
    }
  }
}
