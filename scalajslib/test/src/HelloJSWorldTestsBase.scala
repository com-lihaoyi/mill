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
import os.Path

trait HelloJSWorldTestsBase extends TestSuite {
  def workspacePath: Path
  def scalaVersions: Seq[String] // = Seq("2.13.3", /* "3.0.0-RC1", */ "2.12.12", "2.11.12")
  def scalaJSVersionsAndUseECMA2015
      : Seq[(String, Boolean)] // = Seq(("1.7.1", false), ("1.4.0", false), ("1.3.1", true))
  def matrix = for {
    scala <- scalaVersions
    (scalaJS, useECMAScript2015) <- scalaJSVersionsAndUseECMA2015
    if !(isScala3(scala) && scalaJS != scalaJSVersionsAndUseECMA2015.head._1)
  } yield (scala, scalaJS, useECMAScript2015)

  trait HelloJSWorldBase extends TestUtil.BaseModule {

    abstract class BuildModuleBase(
        val crossScalaVersion: String,
        sjsVersion0: String,
        sjsUseECMA2015: Boolean
    ) extends CrossScalaModule with ScalaJSModule {
      override def millSourcePath = workspacePath
      override def artifactName = "hello-split-world"
      def scalaJSVersion = sjsVersion0
      def useECMAScript2015 = sjsUseECMA2015
    }

    abstract class BuildModuleUtestBase(
        crossScalaVersion: String,
        sjsVersion0: String,
        sjsUseECMA2015: Boolean
    ) extends BuildModuleBase(crossScalaVersion, sjsVersion0, sjsUseECMA2015) {
      object test extends super.Tests with TestModule.Utest {
        override def sources = T.sources { millSourcePath / "src" / "utest" }
        val utestVersion = if (isScala3(crossScalaVersion)) "0.7.7" else "0.7.5"
        override def ivyDeps = Agg(
          ivy"com.lihaoyi::utest::$utestVersion"
        )
      }
    }

    abstract class BuildModuleScalaTestBase(
        crossScalaVersion: String,
        sjsVersion0: String,
        sjsUseECMA2015: Boolean
    ) extends BuildModuleBase(crossScalaVersion, sjsVersion0, sjsUseECMA2015) {
      object test extends super.Tests with TestModule.ScalaTest {
        override def sources = T.sources { millSourcePath / "src" / "scalatest" }
        override def ivyDeps = Agg(
          ivy"org.scalatest::scalatest::3.1.2"
        )
      }
    }
  }

  val millSourcePath: Path

  val helloWorldEvaluator: TestEvaluator

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
      (scala, scalaJS, useECMAScript2015) <- matrix
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
