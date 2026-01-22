package mill.javalib

import mill.*
import mill.api.ExecResult
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.api.Discover

object RunTests extends TestSuite {

  object HelloJavaWithMain extends TestRootModule {
    object app extends JavaModule {
      override def mainClass: T[Option[String]] = Some("hello.Main")
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaDefaultMain extends TestRootModule {
    object app extends JavaModule

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaWithoutMain extends TestRootModule {
    object app extends JavaModule {
      override def mainClass = None
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"

  def tests: Tests = Tests {

    test("runMain") {
      test("runMainObject") - UnitTester(HelloJavaWithMain, resourcePath).scoped { eval =>
        val Right(result) =
          eval.apply(HelloJavaWithMain.app.runMain(
            "hello.Main",
            "testArg"
          )): @unchecked
        assert(result.evalCount > 0)
      }
      test("notRunInvalidMainObject") - UnitTester(
        HelloJavaWithMain,
        resourcePath
      ).scoped { eval =>
        val Left(ExecResult.Failure(msg = "Subprocess failed")) =
          eval.apply(HelloJavaWithMain.app.runMain("Invalid")): @unchecked
      }
      test("notRunWhenCompileFailed") - UnitTester(
        HelloJavaWithMain,
        resourcePath
      ).scoped { eval =>
        os.write.append(
          HelloJavaWithMain.moduleDir / "app/src/Main.java",
          "invalid java syntax"
        )

        val Left(ExecResult.Failure(msg = "Compilation failed")) =
          eval.apply(HelloJavaWithMain.app.runMain("hello.Main")): @unchecked

      }
    }

    test("forkRun") {
      test("runIfMainClassProvided") - UnitTester(HelloJavaWithMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(
          HelloJavaWithMain.app.run(Task.Anon(Args("testArg")))
        ): @unchecked

        assert(result.evalCount > 0)
      }
      test("notRunWithoutMainClass") - UnitTester(
        HelloJavaWithoutMain,
        sourceRoot = resourcePath
      ).scoped { eval =>
        val Left(_: ExecResult.Failure[_]) =
          eval.apply(HelloJavaWithoutMain.app.run()): @unchecked
      }

      test("allLocalMainClasses") - UnitTester(HelloJavaDefaultMain, resourcePath).scoped {
        eval =>
          val Right(result) = eval.apply(HelloJavaDefaultMain.app.allLocalMainClasses): @unchecked

          val found = result.value
          val expected = Seq("hello.Main")
          assert(found == expected)
          found
      }

      test("runDiscoverMainClass") - UnitTester(HelloJavaDefaultMain, resourcePath).scoped {
        eval =>
          // Make sure even if there isn't a main class defined explicitly, it gets
          // discovered and used
          val Right(result) = eval.apply(
            HelloJavaDefaultMain.app.run(Task.Anon(Args("testArg")))
          ): @unchecked

          assert(result.evalCount > 0)
      }
    }

    test("run") {
      test("runIfMainClassProvided") - UnitTester(HelloJavaWithMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(
          HelloJavaWithMain.app.runLocal(Task.Anon(Args("testArg")))
        ): @unchecked

        assert(result.evalCount > 0)
      }
      test("runWithDefaultMain") - UnitTester(HelloJavaDefaultMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(
          HelloJavaDefaultMain.app.runLocal(Task.Anon(Args("testArg")))
        ): @unchecked

        assert(result.evalCount > 0)
      }
      test("notRunWithoutMainClass") - UnitTester(
        HelloJavaWithoutMain,
        sourceRoot = resourcePath
      ).scoped { eval =>
        val Left(_: ExecResult.Failure[_]) =
          eval.apply(HelloJavaWithoutMain.app.runLocal()): @unchecked
      }
    }
  }
}
