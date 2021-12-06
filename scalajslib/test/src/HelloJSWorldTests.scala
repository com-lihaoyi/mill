package mill.scalajslib

import java.util.jar.JarFile
import mill._
import mill.define.Discover
import mill.eval.{EvaluatorPaths, Result}
import mill.scalalib.{CrossScalaModule, DepSyntax, Lib, PublishModule, TestModule}
import mill.testrunner.TestRunner
import mill.scalalib.api.Util.isScala3
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.util.{TestEvaluator, TestUtil}
import utest._

import scala.collection.JavaConverters._
import mill.scalajslib.api._

object HelloJSWorldTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-js-world"

  trait HelloJSWorldModule extends CrossScalaModule with ScalaJSModule with PublishModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("Main")
  }

  object HelloJSWorld extends TestUtil.BaseModule {
    val scalaVersions = Seq("2.13.3", "3.0.0-RC1", "2.12.12", "2.11.12")
    val scalaJSVersionsAndUseECMA2015 =
      Seq(("1.4.0", false), ("1.3.1", true), ("1.0.1", false), ("0.6.33", false))
    val matrix = for {
      scala <- scalaVersions
      (scalaJS, useECMAScript2015) <- scalaJSVersionsAndUseECMA2015
      if !(isScala3(scala) && scalaJS != scalaJSVersionsAndUseECMA2015.head._1)
    } yield (scala, scalaJS, useECMAScript2015)

    object helloJsWorld extends Cross[BuildModule](matrix: _*)
    class BuildModule(val crossScalaVersion: String, sjsVersion0: String, sjsUseECMA2015: Boolean)
        extends HelloJSWorldModule {
      override def artifactName = "hello-js-world"
      def scalaJSVersion = sjsVersion0
      def useECMAScript2015 = sjsUseECMA2015
      def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "hello js world ready for real world publishing",
        url = "https://github.com/lihaoyi/hello-world-publish",
        licenses = Seq(License.Common.Apache2),
        versionControl = VersionControl.github("lihaoyi", "hello-world-publish"),
        developers =
          Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
    }

    object buildUTest extends Cross[BuildModuleUtest](matrix: _*)
    class BuildModuleUtest(crossScalaVersion: String, sjsVersion0: String, sjsUseECMA2015: Boolean)
        extends BuildModule(crossScalaVersion, sjsVersion0, sjsUseECMA2015) {
      object test extends super.Tests with TestModule.Utest {
        override def sources = T.sources { millSourcePath / "src" / "utest" }
        val utestVersion = if (isScala3(crossScalaVersion)) "0.7.7" else "0.7.5"
        override def ivyDeps = Agg(
          ivy"com.lihaoyi::utest::$utestVersion"
        )
      }
    }

    object buildScalaTest extends Cross[BuildModuleScalaTest](matrix: _*)
    class BuildModuleScalaTest(
        crossScalaVersion: String,
        sjsVersion0: String,
        sjsUseECMA2015: Boolean
    ) extends BuildModule(crossScalaVersion, sjsVersion0, sjsUseECMA2015) {
      object test extends super.Tests {
        override def sources = T.sources { millSourcePath / "src" / "scalatest" }
        def testFrameworks = Seq("org.scalatest.tools.Framework")
        override def ivyDeps = Agg(
          ivy"org.scalatest::scalatest::3.1.2"
        )
      }
    }
    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-js-world"

  val helloWorldEvaluator = TestEvaluator.static(HelloJSWorld)

  val mainObject = helloWorldEvaluator.outPath / "src" / "Main.scala"

  def tests: Tests = Tests {
    prepareWorkspace()
    "compile" - {
      def testCompileFromScratch(
          scalaVersion: String,
          scalaJSVersion: String,
          useECMAScript2015: Boolean
      ): Unit = {
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloJSWorld.helloJsWorld(
            scalaVersion,
            scalaJSVersion,
            useECMAScript2015
          ).compile)

        val outPath = result.classes.path
        val outputFiles = os.walk(outPath)
        val expectedClassfiles = compileClassfiles(outPath, scalaVersion, scalaJSVersion)
        assert(
          outputFiles.toSet == expectedClassfiles,
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) =
          helloWorldEvaluator(HelloJSWorld.helloJsWorld(
            scalaVersion,
            scalaJSVersion,
            useECMAScript2015
          ).compile)
        assert(unchangedEvalCount == 0)
      }

      testAllMatrix(
        (scala, scalaJS, useECMAScript2015) =>
          testCompileFromScratch(scala, scalaJS, useECMAScript2015),
        skipECMAScript2015 = false
      )
    }

    def testRun(
        scalaVersion: String,
        scalaJSVersion: String,
        useECMAScript2015: Boolean,
        mode: OptimizeMode
    ): Unit = {
      val task = mode match {
        case FullOpt =>
          HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion, useECMAScript2015).fullOpt
        case FastOpt =>
          HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion, useECMAScript2015).fastOpt
      }
      val Right((result, evalCount)) = helloWorldEvaluator(task)
      val jsFile = result.path
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

    "fullOpt" - {
      testAllMatrix((scala, scalaJS, _) =>
        TestUtil.disableInJava9OrAbove(testRun(scala, scalaJS, false, FullOpt))
      )
    }
    "fastOpt" - {
      testAllMatrix(
        (scala, scalaJS, useECMAScript2015) =>
          TestUtil.disableInJava9OrAbove(testRun(scala, scalaJS, useECMAScript2015, FastOpt)),
        skipECMAScript2015 = false
      )
    }
    "jar" - {
      "containsSJSIRs" - {
        val (scala, scalaJS, useECMAScript2015) = HelloJSWorld.matrix.head
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloJSWorld.helloJsWorld(scala, scalaJS, useECMAScript2015).jar)
        val jar = result.path
        val entries = new JarFile(jar.toIO).entries().asScala.map(_.getName)
        assert(entries.contains("Main$.sjsir"))
      }
    }
    "publish" - {
      def testArtifactId(scalaVersion: String, scalaJSVersion: String, artifactId: String): Unit = {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloJSWorld.helloJsWorld(
          scalaVersion,
          scalaJSVersion,
          false
        ).artifactMetadata)
        assert(result.id == artifactId)
      }
      "artifactId_06" - testArtifactId(
        HelloJSWorld.scalaVersions.head,
        "0.6.33",
        "hello-js-world_sjs0.6_2.13"
      )
      "artifactId_10" - testArtifactId(
        HelloJSWorld.scalaVersions.head,
        "1.0.1",
        "hello-js-world_sjs1_2.13"
      )
      "artifactId_1" - testArtifactId(
        HelloJSWorld.scalaVersions.head,
        "1.4.0",
        "hello-js-world_sjs1_2.13"
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

    def checkUtest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
      val resultMap = runTests(
        if (!cached) HelloJSWorld.buildUTest(scalaVersion, scalaJSVersion, false).test.test()
        else HelloJSWorld.buildUTest(scalaVersion, scalaJSVersion, false).test.testCached
      )

      val mainTests = resultMap("MainTests")
      val argParserTests = resultMap("ArgsParserTests")

      assert(
        mainTests.size == 2,
        mainTests("MainTests.vmName.containJs").status == "Success",
        mainTests("MainTests.vmName.containScala").status == "Success",
        argParserTests.size == 2,
        argParserTests("ArgsParserTests.one").status == "Success",
        argParserTests("ArgsParserTests.two").status == "Failure"
      )
    }

    def checkScalaTest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
      val resultMap = runTests(
        if (!cached) HelloJSWorld.buildScalaTest(scalaVersion, scalaJSVersion, false).test.test()
        else HelloJSWorld.buildScalaTest(scalaVersion, scalaJSVersion, false).test.testCached
      )

      val mainSpec = resultMap("MainSpec")
      val argParserSpec = resultMap("ArgsParserSpec")

      assert(
        mainSpec.size == 2,
        mainSpec("vmName should contain js").status == "Success",
        mainSpec("vmName should contain Scala").status == "Success",
        argParserSpec.size == 2,
        argParserSpec("parse should one").status == "Success",
        argParserSpec("parse should two").status == "Failure"
      )
    }

    "test" - {
      val cached = false
      testAllMatrix(
        (scala, scalaJS, _) => checkUtest(scala, scalaJS, cached),
        skipScala = _.startsWith("2.11.")
      )
      testAllMatrix(
        (scala, scalaJS, _) => checkScalaTest(scala, scalaJS, cached),
        skipScala = isScala3
      )
    }

    "testCached" - {
      val cached = false
      testAllMatrix(
        (scala, scalaJS, _) => checkUtest(scala, scalaJS, cached),
        skipScala = _.startsWith("2.11.")
      )
      testAllMatrix(
        (scala, scalaJS, _) => checkScalaTest(scala, scalaJS, cached),
        skipScala = isScala3
      )
    }

    def checkRun(scalaVersion: String, scalaJSVersion: String, useECMAScript2015: Boolean): Unit = {
      val task = HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion, useECMAScript2015).run()

      val Right((_, evalCount)) = helloWorldEvaluator(task)

      val paths = EvaluatorPaths.resolveDestPaths(helloWorldEvaluator.outPath, task)
      val log = os.read(paths.log)
      assert(
        evalCount > 0,
        log.contains("node"),
        // In Scala.js 1.x, println's are sent to the stdout, not to the logger
        !scalaJSVersion.startsWith("0.6.") || log.contains("Scala.js")
      )
    }

    "run" - {
      testAllMatrix(
        (scala, scalaJS, useECMAScript2015) => checkRun(scala, scalaJS, useECMAScript2015),
        skipECMAScript2015 = false
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
      if (isScala3(scalaVersion)) Set(
        parentDir / "ArgsParser.tasty",
        parentDir / "Main.tasty"
      )
      else Set(
        parentDir / "Main$delayedInit$body.class",
        parentDir / "Main$delayedInit$body.sjsir"
      )

    inAllVersions ++ scalaJSVersionSpecific ++ scalaVersionSpecific
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

  def testAllMatrix(
      f: (String, String, Boolean) => Unit,
      skipScala: String => Boolean = _ => false,
      skipScalaJS: String => Boolean = _ => false,
      skipECMAScript2015: Boolean = true
  ): Unit = {
    for {
      (scala, scalaJS, useECMAScript2015) <- HelloJSWorld.matrix
      if !skipScala(scala)
      if !skipScalaJS(scalaJS)
      if !(skipECMAScript2015 && useECMAScript2015)
    } {
      if (scala.startsWith("2.11.")) {
        TestUtil.disableInJava9OrAbove(f(scala, scalaJS, useECMAScript2015))
      } else {
        f(scala, scalaJS, useECMAScript2015)
      }
    }
  }

}
