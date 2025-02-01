package mill.scalalib

import mill._
import mill.api.Result
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

import HelloWorldTests._
object ScalaRunTests extends TestSuite {

  object HelloWorldDefaultMain extends TestBaseModule {
    object core extends HelloWorldTests.HelloWorldModule
  }

  object HelloWorldWithoutMain extends TestBaseModule {
    object core extends HelloWorldTests.HelloWorldModule {
      override def mainClass = None
    }
  }

  object HelloWorldWithMain extends TestBaseModule {
    object core extends HelloWorldTests.HelloWorldModuleWithMain
  }

  def tests: Tests = Tests {

    test("runMain") {
      test("runMainObject") - UnitTester(HelloWorldTests.HelloWorld, resourcePath).scoped { eval =>
        val runResult = eval.outPath / "core/runMain.dest/hello-mill"

        val Right(result) =
          eval.apply(HelloWorldTests.HelloWorld.core.runMain("Main", runResult.toString))
        assert(result.evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      test("runCross") {
        def cross(eval: UnitTester, v: String, expectedOut: String): Unit = {

          val runResult = eval.outPath / "hello-mill"

          val Right(result) = eval.apply(
            HelloWorldTests.CrossHelloWorld.core(v).runMain("Shim", runResult.toString)
          )

          assert(result.evalCount > 0)

          assert(
            os.exists(runResult),
            os.read(runResult) == expectedOut
          )
        }

        test("v2123") - UnitTester(HelloWorldTests.CrossHelloWorld, resourcePath).scoped { eval =>
          cross(eval, scala2123Version, s"${scala2123Version} leet")

        }
        test("v2124") - UnitTester(HelloWorldTests.CrossHelloWorld, resourcePath).scoped { eval =>
          cross(eval, scala212Version, s"${scala212Version} leet")
        }
        test("v2131") - UnitTester(HelloWorldTests.CrossHelloWorld, resourcePath).scoped { eval =>
          cross(eval, scala213Version, s"${scala213Version} idk")
        }
      }

      test("notRunInvalidMainObject") - UnitTester(
        HelloWorldTests.HelloWorld,
        resourcePath
      ).scoped { eval =>
        val Left(Result.Failure("Subprocess failed", _)) =
          eval.apply(HelloWorldTests.HelloWorld.core.runMain("Invalid"))
      }
      test("notRunWhenCompileFailed") - UnitTester(
        HelloWorldTests.HelloWorld,
        resourcePath
      ).scoped { eval =>
        os.write.append(
          HelloWorldTests.HelloWorld.millSourcePath / "core/src/Main.scala",
          "val x: "
        )

        val Left(Result.Failure("Compilation failed", _)) =
          eval.apply(HelloWorldTests.HelloWorld.core.runMain("Main"))

      }
    }

    test("forkRun") {
      test("runIfMainClassProvided") - UnitTester(HelloWorldWithMain, resourcePath).scoped { eval =>
        val runResult = eval.outPath / "core/run.dest/hello-mill"
        val Right(result) = eval.apply(
          HelloWorldWithMain.core.run(Task.Anon(Args(runResult.toString)))
        )

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
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithoutMain.core.run())
      }

      test("runDiscoverMainClass") - UnitTester(HelloWorldWithoutMain, resourcePath).scoped {
        eval =>
          // Make sure even if there isn't a main class defined explicitly, it gets
          // discovered by Zinc and used
          val runResult = eval.outPath / "core/run.dest/hello-mill"
          val Right(result) = eval.apply(
            HelloWorldWithoutMain.core.run(Task.Anon(Args(runResult.toString)))
          )

          assert(result.evalCount > 0)

          assert(
            os.exists(runResult),
            os.read(runResult) == "hello rockjam, your age is: 25"
          )
      }
    }

    test("run") {
      test("runIfMainClassProvided") - UnitTester(HelloWorldWithMain, resourcePath).scoped { eval =>
        val runResult = eval.outPath / "core/run.dest/hello-mill"
        val Right(result) = eval.apply(
          HelloWorldWithMain.core.runLocal(Task.Anon(Args(runResult.toString)))
        )

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
        )

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
        val Left(Result.Failure(_, None)) = eval.apply(HelloWorldWithoutMain.core.runLocal())
      }
    }
  }
}
