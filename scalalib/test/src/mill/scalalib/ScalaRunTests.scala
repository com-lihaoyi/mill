package mill.scalalib

import mill.*
import mill.api.ExecResult
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import HelloWorldTests.*
import mill.define.Discover
object ScalaRunTests extends TestSuite {

  object HelloWorldDefaultMain extends TestBaseModule {
    object core extends HelloWorldTests.HelloWorldModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldWithoutMain extends TestBaseModule {
    object core extends HelloWorldTests.HelloWorldModule {
      override def mainClass = None
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloWorldWithMain extends TestBaseModule {
    object core extends HelloWorldTests.HelloWorldModuleWithMain

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("run") {
      test("runIfMainClassProvided") - UnitTester(HelloWorldWithMain, resourcePath).scoped { eval =>
        val runResult = eval.outPath / "core/run.dest/hello-mill"
        val Right(result) = eval.apply(
          HelloWorldWithMain.core.runLocal(Task.Anon(Args(runResult.toString)))
        ): @unchecked

        assert(result.evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      test("runWithDefaultMain") - UnitTester(HelloWorldDefaultMain, resourcePath).scoped { eval =>
        val runResult = eval.outPath / "core/run.dest/hello-mill"
        val Right(result) = eval.apply(
          HelloWorldDefaultMain.core.runLocal(Task.Anon(Args(runResult.toString)))
        ): @unchecked

        assert(result.evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      test("notRunWithoutMainClass") - UnitTester(
        HelloWorldWithoutMain,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-no-main"
      ).scoped { eval =>
        val Left(ExecResult.Failure(_)) =
          eval.apply(HelloWorldWithoutMain.core.runLocal()): @unchecked
      }
    }
  }
}
