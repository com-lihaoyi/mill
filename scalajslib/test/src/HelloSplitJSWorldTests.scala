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

object HelloSplitJSWorldTests extends TestSuite with HelloJSWorldTestsBase {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-split-js-world"

  val scalaVersions = Seq("2.13.3", "3.1.0", "2.12.12", "2.11.12")

  val scalaJSVersionsAndUseECMA2015 =
    Seq(("1.7.1", false), ("1.4.0", false), ("1.3.1", true))

  trait HelloJSWorldModule extends CrossScalaModule with ScalaJSModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = None
    override def moduleKind: T[ModuleKind] = T { ModuleKind.ESModule }
    override def moduleSplitStyle: T[ModuleSplitStyle] = T { ModuleSplitStyle.FewestModules }
    override def moduleInitializers: T[Seq[ModuleInitializer]] =
      T { Seq(ModuleInitializer("MainA", "main", "main"), ModuleInitializer("MainB", "main", "b")) }
    override def outputPatterns: T[OutputPatterns] = T { OutputPatterns.fromJSFile("%s.mjs") }
  }

  object HelloJSWorld extends HelloJSWorldBase {

    object helloJsWorld extends Cross[BuildModule](matrix: _*)
    class BuildModule(crossScalaVersion: String, sjsVersion0: String, sjsUseECMA2015: Boolean)
        extends BuildModuleBase(crossScalaVersion, sjsVersion0, sjsUseECMA2015)
        with HelloJSWorldModule
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-split-js-world"

  val helloWorldEvaluator = TestEvaluator.static(HelloJSWorld)

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
      assert(output.replaceAll("\r", "") == expectedOutput)
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
        parentDir / "MainA.tasty",
        parentDir / "MainB.tasty"
      )
      else Set(
        parentDir / "MainA$delayedInit$body.class",
        parentDir / "MainA$delayedInit$body.sjsir"
      )
    inAllVersions ++ scalaVersionSpecific
  }
}
