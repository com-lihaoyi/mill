package mill.scalajslib

import mill._
import mill.define.Discover
import mill.eval.{Evaluator, EvaluatorPaths}
import mill.util._
import mill.scalalib._
import utest._
import mill.scalajslib.api._
object MultiModuleTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "multi-module"
  val sourcePath = os.pwd / "scalajslib" / "test" / "resources" / "multi-module"

  object MultiModule extends TestUtil.BaseModule {
    trait BaseModule extends ScalaJSModule {
      def scalaVersion = "2.13.4" // the last 2.13 with JS 0.6 support
      def scalaJSVersion = sys.props.getOrElse("TEST_SCALAJS_0_6_VERSION", ???)
    }

    object client extends BaseModule {
      override def millSourcePath = workspacePath / "client"
      override def moduleDeps = Seq(shared)
      override def mainClass = Some("Main")
      object test extends Tests with TestModule.Utest {
        override def ivyDeps =
          Agg(ivy"com.lihaoyi::utest::${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}")
      }
    }

    object shared extends BaseModule {
      override def millSourcePath = workspacePath / "shared"
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val evaluator = TestEvaluator.static(MultiModule)

  def tests: Tests = Tests {
    prepareWorkspace()

    def checkOpt(optimize: Boolean) = {
      val task = if (optimize) MultiModule.client.fullOpt else MultiModule.client.fastOpt
      val Right((linked, evalCount)) = evaluator(task)

      val runOutput = ScalaJsUtils.runJS(linked.path)
      assert(
        evalCount > 0,
        runOutput == "Hello from Scala.js, result is: 3\n"
      )
    }

    "fastOpt" - checkOpt(optimize = false)
    "fullOpt" - checkOpt(optimize = true)

    test("test") {
      val Right(((_, testResults), evalCount)) = evaluator(MultiModule.client.test.test())

      assert(
        evalCount > 0,
        testResults.size == 3,
        testResults.forall(_.status == "Success")
      )
    }

    test("run") {
      val command = MultiModule.client.run()

      val Right((_, evalCount)) = evaluator(command)

      val paths = EvaluatorPaths.resolveDestPaths(evaluator.outPath, command)
      val log = os.read(paths.log)
      assert(
        evalCount > 0,
        log.contains("node"),
        log.contains("Hello from Scala.js, result is: 3")
      )
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(sourcePath, workspacePath)
  }

}
