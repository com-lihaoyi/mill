package mill.scalajslib

import mill._
import mill.api.Discover
import mill.scalalib.{DepSyntax, PublishModule, ScalaModule, TestModule}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest._

import mill.javalib.api.JvmWorkerUtil

object CompileLinkTests extends TestSuite {
  trait HelloJSWorldModule
      extends ScalaModule with ScalaJSModule with PublishModule
      with Cross.Module2[String, String] {
    val (crossScalaVersion, sjsVersion0) = (crossValue, crossValue2)
    def scalaVersion = crossScalaVersion
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("Main")
  }

  object HelloJSWorld extends TestRootModule {
    val matrix = Seq("2.13.15" -> "1.19.0", "3.7.1" -> "1.19.0")

    object build extends Cross[RootModule](matrix)
    trait RootModule extends HelloJSWorldModule {
      override def artifactName = "hello-js-world"
      def scalaJSVersion = sjsVersion0
      def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "hello js world ready for real world publishing",
        url = "https://github.com/lihaoyi/hello-world-publish",
        licenses = Seq(License.Common.Apache2),
        versionControl = VersionControl.github("lihaoyi", "hello-world-publish"),
        developers =
          Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )

      object `test-utest` extends ScalaJSTests with TestModule.Utest {
        override def sources = Task.Sources("src/utest")
        override def utestVersion = "0.8.9"
      }

      object `test-scalatest` extends ScalaJSTests with TestModule.ScalaTest {
        override def sources = Task.Sources { "src/scalatest" }
        override def mvnDeps = Seq(
          mvn"org.scalatest::scalatest::3.1.2"
        )
      }

    }
    object inherited extends ScalaJSModule {
      val (scala, scalaJS) = matrix.head
      def scalacOptions = Seq("-deprecation")
      def scalaOrganization = "org.example"
      def scalaVersion = scala
      def scalaJSVersion = scalaJS
      object test extends ScalaJSTests with TestModule.Utest
    }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-js-world"

  def testRun(
      scalaVersion: String,
      scalaJSVersion: String,
      optimize: Boolean,
      legacy: Boolean
  ): Unit = UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
    val module = HelloJSWorld.build(scalaVersion, scalaJSVersion)
    val jsFile =
      if (legacy) {
        val task = if (optimize) module.fullLinkJS else module.fastLinkJS
        val Right(result) = eval(task): @unchecked
        result.value.dest.path
      } else {
        val task = if (optimize) module.fullLinkJS else module.fastLinkJS
        val Right(result) = eval(task): @unchecked
        result.value.dest.path / result.value.publicModules.head.jsFileName
      }
    val output = ScalaJsUtils.runJS(jsFile)
    assert(output == "Hello Scala.js\n")
    val sourceMap = jsFile / os.up / (jsFile.last + ".map")
    assert(sourceMap.toIO.exists()) // sourceMap file was generated
    assert(
      os.read(jsFile).contains(s"//# sourceMappingURL=${sourceMap.toNIO.getFileName}")
    ) // jsFile references sourceMap
    assert(ujson.read(sourceMap.toIO).obj.get("file").exists(
      _.str == jsFile.toNIO.getFileName.toString
    )) // sourceMap references jsFile
  }

  def tests: Tests = Tests {
    test("compile") - UnitTester(HelloJSWorld, millSourcePath).scoped { eval =>
      def testCompileFromScratch(scalaVersion: String, scalaJSVersion: String): Unit = {
        val Right(result) =
          eval(HelloJSWorld.build(scalaVersion, scalaJSVersion).compile): @unchecked

        val outPath = result.value.classes.path
        val outputFiles = os.walk(outPath)
        val expectedClassfiles = compileClassfiles(outPath, scalaVersion, scalaJSVersion)
        assert(
          outputFiles.toSet == expectedClassfiles,
          result.evalCount > 0
        )

        // don't recompile if nothing changed
        val Right(result2) =
          eval(HelloJSWorld.build(scalaVersion, scalaJSVersion).compile): @unchecked
        assert(result2.evalCount == 0)
      }

      testAllMatrix((scala, scalaJS) => testCompileFromScratch(scala, scalaJS))
    }

    test("fastLinkJS") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = false)
      )
    }
    test("fullLinkJS") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = false)
      )
    }
  }

  def compileClassfiles(parentDir: os.Path, scalaVersion: String, scalaJSVersion: String) = {
    val inAllVersions = Set(
      parentDir / "ArgsParser$.class",
      parentDir / "ArgsParser$.sjsir",
      parentDir / "ArgsParser.class",
      parentDir / "Main.class",
      parentDir / "Main$.class",
      parentDir / "Main$.sjsir"
    )
    val scalaJSVersionSpecific =
      if (scalaJSVersion.startsWith("1.")) Set(
        parentDir / "ArgsParser.sjsir",
        parentDir / "Main.sjsir"
      )
      else Set.empty
    val scalaVersionSpecific =
      if (JvmWorkerUtil.isScala3(scalaVersion)) Set(
        parentDir / "ArgsParser.tasty",
        parentDir / "Main.tasty"
      )
      else Set(
        parentDir / "Main$delayedInit$body.class",
        parentDir / "Main$delayedInit$body.sjsir"
      )

    inAllVersions ++ scalaJSVersionSpecific ++ scalaVersionSpecific
  }

  def testAllMatrix(
      f: (String, String) => Unit,
      skipScala: String => Boolean = _ => false,
      skipScalaJS: String => Boolean = _ => false
  ): Unit = {
    for {
      (scala, scalaJS) <- HelloJSWorld.matrix
      if !skipScala(scala)
      if !skipScalaJS(scalaJS)
    } {
      f(scala, scalaJS)
    }
  }

}
