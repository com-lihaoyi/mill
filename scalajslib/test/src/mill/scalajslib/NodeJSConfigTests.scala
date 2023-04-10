package mill.scalajslib

import mill._
import mill.define.Discover
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.scalalib.{CrossScalaModule, DepSyntax, ScalaModule, TestModule}
import mill.util.{TestEvaluator, TestUtil}
import utest._
import mill.scalajslib.api._

import scala.util.Properties

object NodeJSConfigTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-js-world"
  val scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  val scalaJSVersion = sys.props.getOrElse("TEST_SCALAJS_VERSION", ???)
  val utestVersion = sys.props.getOrElse("TEST_UTEST_VERSION", ???)
  val nodeArgsEmpty = List()
  val nodeArgs2G = List("--max-old-space-size=2048")
  val nodeArgs4G = List("--max-old-space-size=4096")

  trait HelloJSWorldModule
      extends ScalaModule
      with ScalaJSModule
      with Cross.Module[(String, List[String])] {
    val (crossScalaVersion, nodeArgs) = crossValue
    def scalaVersion = crossScalaVersion
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("Main")
  }

  object HelloJSWorld extends TestUtil.BaseModule {
    val matrix = for {
      scala <- Seq(scalaVersion)
      nodeArgs <- Seq(nodeArgsEmpty, nodeArgs2G)
    } yield (scala, nodeArgs)

    object helloJsWorld extends Cross[RootModule](matrix)
    trait RootModule extends HelloJSWorldModule {

      override def artifactName = "hello-js-world"
      def scalaJSVersion = NodeJSConfigTests.scalaJSVersion
      override def jsEnvConfig = T { JsEnvConfig.NodeJs(args = nodeArgs) }
    }

    object buildUTest extends Cross[BuildModuleUtest](matrix)
    trait BuildModuleUtest extends RootModule {
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

    test("test") - {

      def checkUtest(nodeArgs: List[String], notNodeArgs: List[String]) =
        if (Properties.isJavaAtLeast(17)) "skipped on Java 17+"
        else checkLog(
          HelloJSWorld.buildUTest(scalaVersion, nodeArgs).test.test(),
          nodeArgs,
          notNodeArgs
        )

      test("test") - checkUtest(nodeArgsEmpty, nodeArgs2G)
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
