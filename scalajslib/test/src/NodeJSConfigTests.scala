package mill.scalajslib

import mill._
import mill.define.Discover
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.scalalib.{CrossScalaModule, DepSyntax, TestModule}
import mill.util.{TestEvaluator, TestUtil}
import utest._
import mill.scalajslib.api._

object NodeJSConfigTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-js-world"
  val scalaVersion = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  val scalaJSVersion = "0.6.33"
  val utestVersion = "0.7.5"
  val nodeArgsEmpty = List()
  val nodeArgs2G = List("--max-old-space-size=2048")
  val nodeArgs4G = List("--max-old-space-size=4096")

  trait HelloJSWorldModule extends CrossScalaModule with ScalaJSModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("Main")
  }

  object HelloJSWorld extends TestUtil.BaseModule {
    val matrix = for {
      scala <- Seq(scalaVersion)
      nodeArgs <- Seq(nodeArgsEmpty, nodeArgs2G)
    } yield (scala, nodeArgs)

    object helloJsWorld extends Cross[BuildModule](matrix: _*)
    class BuildModule(val crossScalaVersion: String, nodeArgs: List[String])
        extends HelloJSWorldModule {
      override def artifactName = "hello-js-world"
      def scalaJSVersion = NodeJSConfigTests.scalaJSVersion
      override def jsEnvConfig = T { JsEnvConfig.NodeJs(args = nodeArgs) }
    }

    object buildUTest extends Cross[BuildModuleUtest](matrix: _*)
    class BuildModuleUtest(crossScalaVersion: String, nodeArgs: List[String])
        extends BuildModule(crossScalaVersion, nodeArgs) {
      object test extends super.Tests with TestModule.Utest {
        override def sources = T.sources { millSourcePath / "src" / "utest" }
        override def ivyDeps = Agg(
          ivy"com.lihaoyi::utest::$utestVersion"
        )
        override def jsEnvConfig = T { JsEnvConfig.NodeJs(args = nodeArgs) }
      }
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-js-world"

  val helloWorldEvaluator = TestEvaluator.static(HelloJSWorld)

  val mainObject = helloWorldEvaluator.outPath / "src" / "Main.scala"

  def tests: Tests = Tests {
    prepareWorkspace()

    def checkLog(command: define.Command[_], nodeArgs: List[String], notNodeArgs: List[String]) = {
      helloWorldEvaluator(command)
      val paths = EvaluatorPaths.resolveDestPaths(helloWorldEvaluator.outPath, command)
      val log = os.read(paths.log)
      assert(
        nodeArgs.forall(log.contains),
        notNodeArgs.forall(!log.contains(_))
      )
    }

    "test" - {

      def checkUtest(nodeArgs: List[String], notNodeArgs: List[String]) = {
        checkLog(HelloJSWorld.buildUTest(scalaVersion, nodeArgs).test.test(), nodeArgs, notNodeArgs)
      }

      "test" - checkUtest(nodeArgsEmpty, nodeArgs2G)
      "test2G" - checkUtest(nodeArgs2G, nodeArgs4G)
    }

    def checkRun(nodeArgs: List[String], notNodeArgs: List[String]): Unit = {
      checkLog(HelloJSWorld.helloJsWorld(scalaVersion, nodeArgs).run(), nodeArgs, notNodeArgs)
    }

    "run" - {
      "run" - checkRun(nodeArgsEmpty, nodeArgs2G)
      "run2G" - checkRun(nodeArgs2G, nodeArgs4G)
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
