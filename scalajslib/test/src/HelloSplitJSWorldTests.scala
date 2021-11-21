package mill.scalajslib

import java.util.jar.JarFile
import mill._
import mill.define.Discover
import mill.eval.{EvaluatorPaths, Result}
import mill.scalalib.{CrossScalaModule, DepSyntax, Lib, PublishModule, TestModule, TestRunner}
import mill.scalalib.api.Util.isScala3
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.util.{TestEvaluator, TestUtil}
import utest._

import scala.collection.JavaConverters._
import mill.scalajslib.api._

object HelloSplitJSWorldTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-split-js-world"

  trait HelloJSWorldModule extends CrossScalaModule with ScalaJSModule with PublishModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = None
    override def moduleKind: T[ModuleKind] = T { ModuleKind.ESModule }
    override def moduleSplitStyle: T[ModuleSplitStyle] = T { ModuleSplitStyle.FewestModules }
    override def moduleInitializers: T[Seq[ModuleInitializer]] =
      T { Seq(ModuleInitializer("MainA", "main", "main"), ModuleInitializer("MainB", "main", "b")) }
    override def outputPatterns: T[OutputPatterns] = T { OutputPatterns.fromJSFile("%s.mjs") }
  }

  object HelloJSWorld extends TestUtil.BaseModule {
    val scalaVersions = Seq("2.13.3", /* "3.0.0-RC1", */ "2.12.12", "2.11.12")
    val scalaJSVersionsAndUseECMA2015 =
      Seq(("1.7.1", false), ("1.4.0", false), ("1.3.1", true))
    val matrix = for {
      scala <- scalaVersions
      (scalaJS, useECMAScript2015) <- scalaJSVersionsAndUseECMA2015
      if !(isScala3(scala) && scalaJS != scalaJSVersionsAndUseECMA2015.head._1)
    } yield (scala, scalaJS, useECMAScript2015)

    object helloJsWorld extends Cross[BuildModule](matrix: _*)
    class BuildModule(val crossScalaVersion: String, sjsVersion0: String, sjsUseECMA2015: Boolean)
        extends HelloJSWorldModule {
      override def artifactName = "hello-split-js-world"
      def scalaJSVersion = sjsVersion0
      def useECMAScript2015 = sjsUseECMA2015
      def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "hello split js world ready for real world publishing",
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

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-split-js-world"

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
          HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion, useECMAScript2015).fullLinkJS
        case FastOpt =>
          HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion, useECMAScript2015).fastLinkJS
      }
      val Right((result, evalCount)) = helloWorldEvaluator(task)
      val jsFile = result.path / "bootstrap.mjs"
      val jsFileContent =
        """
          |import { startA } from './main.mjs'
          |import { startB } from './b.mjs'
          |
          |startA()
          |startB()
          |""".stripMargin
      os.write(jsFile, jsFileContent)
      val output = ScalaJsUtils.runJS(jsFile)
      println("output", output)
      val expectedOutput =
       """|Hello A Scala.js
          |Module init A
          |Hello B Scala.js
          |Module init B
          |Hello entrypoint AScala.js
          |Hello entrypoint BScala.js
          |""".stripMargin
      assert(output == expectedOutput)
      val sourceMapA = result.path / "main.mjs.map"
      val sourceMapB = result.path / "b.mjs.map"
      assert(sourceMapA.toIO.exists()) // sourceMap file was generated
      assert(sourceMapB.toIO.exists()) // sourceMap file was generated
      assert(ujson.read(sourceMapA.toIO).obj.get("file").exists(
        _.str == "main.mjs"
      )) // sourceMap references jsFile
      assert(ujson.read(sourceMapB.toIO).obj.get("file").exists(
        _.str == "b.mjs"
      )) // sourceMap references jsFile
    }

    "fullLinkJS" - {
      testAllMatrix((scala, scalaJS, _) =>
        TestUtil.disableInJava9OrAbove(testRun(scala, scalaJS, false, FullOpt))
      )
    }
    "fastLinkJS" - {
      testAllMatrix(
        (scala, scalaJS, useECMAScript2015) =>
          TestUtil.disableInJava9OrAbove(testRun(scala, scalaJS, useECMAScript2015, FastOpt)),
        skipECMAScript2015 = false
      )
    }
//    "jar" - {
//      "containsSJSIRs" - {
//        val (scala, scalaJS, useECMAScript2015) = HelloJSWorld.matrix.head
//        val Right((result, evalCount)) =
//          helloWorldEvaluator(HelloJSWorld.helloJsWorld(scala, scalaJS, useECMAScript2015).jar)
//        val jar = result.path
//        val entries = new JarFile(jar.toIO).entries().asScala.map(_.getName)
//        assert(entries.contains("MainA$.sjsir"))
//        assert(entries.contains("MainB$.sjsir"))
//      }
//    }
//    "publish" - {
//      def testArtifactId(scalaVersion: String, scalaJSVersion: String, artifactId: String): Unit = {
//        val Right((result, evalCount)) = helloWorldEvaluator(HelloJSWorld.helloJsWorld(
//          scalaVersion,
//          scalaJSVersion,
//          false
//        ).artifactMetadata)
//        assert(result.id == artifactId)
//      }
//      "artifactId_1" - testArtifactId(
//        HelloJSWorld.scalaVersions.head,
//        "1.7.1",
//        "hello-split-js-world_sjs1_2.13"
//      )
//    }

//    def runTests(testTask: define.NamedTask[(String, Seq[TestRunner.Result])])
//        : Map[String, Map[String, TestRunner.Result]] = {
//      val Left(Result.Failure(_, Some(res))) = helloWorldEvaluator(testTask)
//
//      val (doneMsg, testResults) = res
//      testResults
//        .groupBy(_.fullyQualifiedName)
//        .mapValues(_.map(e => e.selector -> e).toMap)
//        .toMap
//    }

//    def checkUtest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
//      val resultMap = runTests(
//        if (!cached) HelloJSWorld.buildUTest(scalaVersion, scalaJSVersion, false).test.test()
//        else HelloJSWorld.buildUTest(scalaVersion, scalaJSVersion, false).test.testCached
//      )
//
//      val mainTests = resultMap("MainTests")
//      val argParserTests = resultMap("ArgsParserTests")
//
//      assert(
//        mainTests.size == 2,
//        mainTests("MainTests.vmName.containJs").status == "Success",
//        mainTests("MainTests.vmName.containScala").status == "Success",
//        argParserTests.size == 2,
//        argParserTests("ArgsParserTests.one").status == "Success",
//        argParserTests("ArgsParserTests.two").status == "Failure"
//      )
//    }

//    def checkScalaTest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
//      val resultMap = runTests(
//        if (!cached) HelloJSWorld.buildScalaTest(scalaVersion, scalaJSVersion, false).test.test()
//        else HelloJSWorld.buildScalaTest(scalaVersion, scalaJSVersion, false).test.testCached
//      )
//
//      val mainSpec = resultMap("MainSpec")
//      val argParserSpec = resultMap("ArgsParserSpec")
//
//      assert(
//        mainSpec.size == 2,
//        mainSpec("vmName should contain js").status == "Success",
//        mainSpec("vmName should contain Scala").status == "Success",
//        argParserSpec.size == 2,
//        argParserSpec("parse should one").status == "Success",
//        argParserSpec("parse should two").status == "Failure"
//      )
//    }

//    "test" - {
//      val cached = false
//      testAllMatrix(
//        (scala, scalaJS, _) => checkUtest(scala, scalaJS, cached),
//        skipScala = _.startsWith("2.11.")
//      )
//      testAllMatrix(
//        (scala, scalaJS, _) => checkScalaTest(scala, scalaJS, cached),
//        skipScala = isScala3
//      )
//    }
//
//    "testCached" - {
//      val cached = false
//      testAllMatrix(
//        (scala, scalaJS, _) => checkUtest(scala, scalaJS, cached),
//        skipScala = _.startsWith("2.11.")
//      )
//      testAllMatrix(
//        (scala, scalaJS, _) => checkScalaTest(scala, scalaJS, cached),
//        skipScala = isScala3
//      )
//    }

//    def checkRun(scalaVersion: String, scalaJSVersion: String, useECMAScript2015: Boolean): Unit = {
//      val task = HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion, useECMAScript2015).run()
//
//      val Right((_, evalCount)) = helloWorldEvaluator(task)
//
//      val paths = EvaluatorPaths.resolveDestPaths(helloWorldEvaluator.outPath, task)
//      val log = os.read(paths.log)
//      assert(
//        evalCount > 0,
//        log.contains("node"),
//        // In Scala.js 1.x, println's are sent to the stdout, not to the logger
//        !scalaJSVersion.startsWith("0.6.") || log.contains("Scala.js")
//      )
//    }

//    "run" - {
//      testAllMatrix(
//        (scala, scalaJS, useECMAScript2015) => checkRun(scala, scalaJS, useECMAScript2015),
//        skipECMAScript2015 = false
//      )
//    }
  }

  def compileClassfiles(parentDir: os.Path, scalaVersion: String, scalaJSVersion: String) = {
    val inAllVersions = Set(
      parentDir / "MainA$.class",
      parentDir / "MainA$.sjsir",
      parentDir / "MainA.class",
      parentDir / "MainA.sjsir",
      parentDir / "MainB$.class",
      parentDir / "MainB$.sjsir",
      parentDir / "MainB.class",
      parentDir / "MainB.sjsir"
    )
    val scalaVersionSpecific =
      if (isScala3(scalaVersion)) Set(
        parentDir / "ArgsParser.tasty",
        parentDir / "Main.tasty"
      )
      else Set(
        parentDir / "MainA$delayedInit$body.class",
        parentDir / "MainA$delayedInit$body.sjsir"
      )
    inAllVersions ++ scalaVersionSpecific
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
