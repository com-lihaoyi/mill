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

    test("runMain") {
      test("runMainObject") - UnitTester(HelloWorldTests.HelloWorld, resourcePath).scoped { eval =>
        val runResult = eval.outPath / "core/runMain.dest/hello-mill"

        val Right(result) =
          eval.apply(HelloWorldTests.HelloWorld.core.runMain(
            "Main",
            runResult.toString
          )): @unchecked
        assert(result.evalCount > 0)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      test("runCross") {
        def cross(eval: UnitTester, v: String, expectedOut: String): String = {

          val runResult = eval.outPath / "hello-mill"

          val Right(classes) =
            eval.apply(HelloWorldTests.CrossHelloWorld.core(v).allLocalMainClasses): @unchecked
          val found = classes.value
          val expected = Seq("Main", "Shim")
          assert(found == expected)

          val Right(result) = eval.apply(
            HelloWorldTests.CrossHelloWorld.core(v).runMain("Shim", runResult.toString)
          ): @unchecked

          assert(result.evalCount > 0)

          assert(
            os.exists(runResult),
            os.read(runResult) == expectedOut
          )
          expectedOut
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
        val Left(ExecResult.Failure("Subprocess failed")) =
          eval.apply(HelloWorldTests.HelloWorld.core.runMain("Invalid")): @unchecked
      }
      test("notRunWhenCompileFailed") - UnitTester(
        HelloWorldTests.HelloWorld,
        resourcePath
      ).scoped { eval =>
        os.write.append(
          HelloWorldTests.HelloWorld.moduleDir / "core/src/Main.scala",
          "val x: "
        )

        val Left(ExecResult.Failure("Compilation failed")) =
          eval.apply(HelloWorldTests.HelloWorld.core.runMain("Main")): @unchecked

      }
    }

    test("forkRun") {
      test("runIfMainClassProvided") - UnitTester(HelloWorldWithMain, resourcePath).scoped { eval =>
        val runResult = eval.outPath / "core/run.dest/hello-mill"
        val Right(result) = eval.apply(
          HelloWorldWithMain.core.run(Task.Anon(Args(runResult.toString)))
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
          eval.apply(HelloWorldWithoutMain.core.run()): @unchecked
      }

      test("allLocalMainClasses") - UnitTester(HelloWorldWithoutMain, resourcePath).scoped {
        eval =>
          val Right(result) = eval.apply(HelloWorldWithoutMain.core.allLocalMainClasses): @unchecked

          val found = result.value
          val expected = Seq("Main")
          assert(found == expected)
          found
      }

      test("runDiscoverMainClass") - UnitTester(HelloWorldWithoutMain, resourcePath).scoped {
        eval =>
          // Make sure even if there isn't a main class defined explicitly, it gets
          // discovered by Zinc and used
          val runResult = eval.outPath / "core/run.dest/hello-mill"
          val Right(result) = eval.apply(
            HelloWorldWithoutMain.core.run(Task.Anon(Args(runResult.toString)))
          ): @unchecked

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
