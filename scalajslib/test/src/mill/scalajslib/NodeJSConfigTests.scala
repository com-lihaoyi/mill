package mill.scalajslib

import mill._
import mill.define.Discover
import mill.define.ExecutionPaths
import mill.scalalib.{DepSyntax, ScalaModule, TestModule}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._
import mill.scalajslib.api._

import scala.util.Properties

object NodeJSConfigTests extends TestSuite {
  val scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  val scalaJSVersion = sys.props.getOrElse("TEST_SCALAJS_VERSION", ???)
  val utestVersion = sys.props.getOrElse("TEST_UTEST_VERSION", ???)
  val nodeArgsEmpty = List()
  val nodeArgs2G = List("--max-old-space-size=2048")
  val nodeArgs4G = List("--max-old-space-size=4096")

  trait HelloJSWorldModule
      extends ScalaModule
      with ScalaJSModule
      with Cross.Module2[String, List[String]] {
    val (crossScalaVersion, nodeArgs) = (crossValue, crossValue2)
    def scalaVersion = crossScalaVersion
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("Main")
  }

  object HelloJSWorld extends TestBaseModule {
    val matrix = for {
      scala <- Seq(scalaVersion)
      nodeArgs <- Seq(nodeArgsEmpty, nodeArgs2G)
    } yield (scala, nodeArgs)

    object build extends Cross[RootModule](matrix)
    trait RootModule extends HelloJSWorldModule {

      override def artifactName = "hello-js-world"
      def scalaJSVersion = NodeJSConfigTests.scalaJSVersion
      override def jsEnvConfig = Task { JsEnvConfig.NodeJs(args = nodeArgs) }

      object `test-utest` extends ScalaJSTests with TestModule.Utest {
        override def sources = Task.Sources { this.moduleDir / "src/utest" }
        override def ivyDeps = Seq(
          ivy"com.lihaoyi::utest::$utestVersion"
        )
        override def jsEnvConfig = Task { JsEnvConfig.NodeJs(args = nodeArgs) }
      }
    }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-js-world"

  val helloWorldEvaluator = UnitTester(HelloJSWorld, millSourcePath)

  val mainObject = helloWorldEvaluator.outPath / "src/Main.scala"

  def tests: Tests = Tests {
    def checkLog(command: define.Command[?], nodeArgs: List[String], notNodeArgs: List[String]) = {
      helloWorldEvaluator(command)
      val paths = ExecutionPaths.resolve(helloWorldEvaluator.outPath, command)
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
          HelloJSWorld.build(scalaVersion, nodeArgs).`test-utest`.testForked(),
          nodeArgs,
          notNodeArgs
        )

      test("test") - checkUtest(nodeArgsEmpty, nodeArgs2G)
      test("test2G") - checkUtest(nodeArgs2G, nodeArgs4G)
    }

    def checkRun(nodeArgs: List[String], notNodeArgs: List[String]): Unit = {
      checkLog(HelloJSWorld.build(scalaVersion, nodeArgs).run(), nodeArgs, notNodeArgs)
    }

    test("run") {
      test("run") - checkRun(nodeArgsEmpty, nodeArgs2G)
      test("run2G") - checkRun(nodeArgs2G, nodeArgs4G)
    }
  }
}
