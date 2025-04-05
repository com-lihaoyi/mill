package mill.scalajslib

import mill._
import mill.define.Discover
import mill.define.ExecutionPaths
import mill.scalalib._
import mill.testkit.{UnitTester, TestBaseModule}
import utest._
object MultiModuleTests extends TestSuite {
  val sourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "multi-module"

  object MultiModule extends TestBaseModule {
    trait BaseModule extends ScalaJSModule {
      def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      def scalaJSVersion = sys.props.getOrElse("TEST_SCALAJS_VERSION", ???)
    }

    object client extends BaseModule {
      override def moduleDir = MultiModule.moduleDir / "client"
      override def moduleDeps = Seq(shared)
      override def mainClass = Some("Main")
      object test extends ScalaJSTests with TestModule.Utest {
        override def ivyDeps =
          Seq(ivy"com.lihaoyi::utest::${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}")
      }
    }

    object shared extends BaseModule {
      override def moduleDir = MultiModule.moduleDir / "shared"
    }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val evaluator = UnitTester(MultiModule, sourcePath)

  def tests: Tests = Tests {
    def checkOpt(optimize: Boolean) = {
      val task = if (optimize) MultiModule.client.fullLinkJS else MultiModule.client.fastLinkJS
      val Right(result) = evaluator(task): @unchecked

      val runOutput = ScalaJsUtils.runJS(result.value.dest.path / "main.js")
      assert(
        result.evalCount > 0,
        runOutput == "Hello from Scala.js, result is: 3\n"
      )
    }

    test("fastOpt") - checkOpt(optimize = false)
    test("fullOpt") - checkOpt(optimize = true)

    test("test") {
      val Right(result) = evaluator(MultiModule.client.test.testForked()): @unchecked

      assert(
        result.evalCount > 0,
        result.value._2.size == 3,
        result.value._2.forall(_.status == "Success")
      )
    }

    test("run") {
      val command = MultiModule.client.run()

      val Right(result) = evaluator(command): @unchecked

      val paths = ExecutionPaths.resolve(evaluator.outPath, command)
      val log = os.read(paths.log)
      assert(
        result.evalCount > 0,
        log.contains("node")
        // TODO: re-enable somehow
        // In Scala.js 1.x, the stdout is no longer sent to the log, so this check doesn't work
        // log.contains("Hello from Scala.js, result is: 3")
      )
    }
  }
}
