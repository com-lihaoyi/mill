package mill.scalajslib

import ammonite.ops._
import mill._
import mill.define.Discover
import mill.eval.Evaluator
import mill.scalalib.{CrossScalaModule, DepSyntax}
import mill.util.{TestEvaluator, TestUtil}
import utest._


object NodeJSConfigTests extends TestSuite {
  val workspacePath =  TestUtil.getOutPathStatic() / "hello-js-world"
  val scalaVersion = "2.12.4"
  val scalaJSVersion = "0.6.22"
  val utestVersion = "0.6.3"
  val scalaTestVersion = "3.0.4"
  val nodeArg2G = "--max-old-space-size=2048"

  trait HelloJSWorldModule extends CrossScalaModule with ScalaJSModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("Main")
  }

  object HelloJSWorld extends TestUtil.BaseModule {
    val matrix = for {
      scala <- Seq(scalaVersion)
      nodeArgs <- Seq(Seq(), Seq(nodeArg2G))
    } yield (scala, nodeArgs)

    object helloJsWorld extends Cross[BuildModule](matrix:_*)
    class BuildModule(val crossScalaVersion: String, nodeArgs: List[String]) extends HelloJSWorldModule {
      override def artifactName = "hello-js-world"
      def scalaJSVersion = NodeJSConfigTests.scalaJSVersion
      override def nodeJSConfig = T { NodeJSConfig(args = nodeArgs) }
    }

    object buildUTest extends Cross[BuildModuleUtest](matrix:_*)
    class BuildModuleUtest(crossScalaVersion: String, nodeArgs: List[String])
      extends BuildModule(crossScalaVersion, nodeArgs) {
      object test extends super.Tests {
        override def sources = T.sources{ millSourcePath / 'src / 'utest }
        def testFrameworks = Seq("utest.runner.Framework")
        override def ivyDeps = Agg(
          ivy"com.lihaoyi::utest::$utestVersion"
        )
        override def nodeJSConfig = T { NodeJSConfig(args = nodeArgs) }
      }
    }

    object buildScalaTest extends Cross[BuildModuleScalaTest](matrix:_*)
    class BuildModuleScalaTest(crossScalaVersion: String, nodeArgs: List[String])
      extends BuildModule(crossScalaVersion, nodeArgs) {
      object test extends super.Tests {
        override def sources = T.sources{ millSourcePath / 'src / 'scalatest }
        def testFrameworks = Seq("org.scalatest.tools.Framework")
        override def ivyDeps = Agg(
          ivy"org.scalatest::scalatest::$scalaTestVersion"
        )
        override def nodeJSConfig = T { NodeJSConfig(args = nodeArgs) }
      }
    }
    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = pwd / 'scalajslib / 'test / 'resources / "hello-js-world"

  val helloWorldEvaluator = TestEvaluator.static(HelloJSWorld)

  val mainObject = helloWorldEvaluator.outPath / 'src / "Main.scala"

  def tests: Tests = Tests {
    prepareWorkspace()

    def checkLog(command: define.Command[_], nodeArgs: List[String]) = {
      helloWorldEvaluator(command)
      val paths = Evaluator.resolveDestPaths(
        helloWorldEvaluator.outPath,
        command.ctx.segments
      )
      val log = read(paths.log)
      assert(nodeArgs.forall(log.contains))
    }

    'test - {

      def checkUtest(nodeArgs: List[String]) = {
        checkLog(HelloJSWorld.buildUTest(scalaVersion, nodeArgs).test.test(), nodeArgs)
      }

      def checkScalaTest(nodeArgs: List[String]) = {
        checkLog(HelloJSWorld.buildScalaTest(scalaVersion, nodeArgs).test.test(), nodeArgs)
      }

      'utest - checkUtest(List())
      'utest_2G - checkUtest(List(nodeArg2G))
      'scalaTest - checkScalaTest(List())
      'scalaTest_2G - checkScalaTest(List(nodeArg2G))
    }

    def checkRun(nodeArgs: List[String]): Unit = {
      checkLog(HelloJSWorld.helloJsWorld(scalaVersion, nodeArgs).run(), nodeArgs)
    }

    'run - {
      'run - checkRun(List())
      'run_2G  - checkRun(List(nodeArg2G))
    }
  }

  def prepareWorkspace(): Unit = {
    rm(workspacePath)
    mkdir(workspacePath / up)
    cp(millSourcePath, workspacePath)
  }

}
