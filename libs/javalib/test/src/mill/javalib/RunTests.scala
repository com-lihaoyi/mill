package mill.javalib

import mill.*
import mill.api.ExecResult
import mill.api.daemon.LauncherSubprocess
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.api.Discover

object RunTests extends TestSuite {

  object HelloJavaWithMain extends TestRootModule {
    object core extends JavaModule
    object app extends JavaModule {
      override def moduleDeps = Seq(core)
      override def mainClass: T[Option[String]] = Some("hello.Main")
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaDefaultMain extends TestRootModule {
    object core extends JavaModule
    object app extends JavaModule {
      override def moduleDeps = Seq(core)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object HelloJavaWithoutMain extends TestRootModule {
    object core extends JavaModule
    object app extends JavaModule {
      override def moduleDeps = Seq(core)
      override def mainClass = None
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"
  val noMainResourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java-no-main"

  def tests: Tests = Tests {

    test("runMain") {
      test("runMainObject") - UnitTester(HelloJavaWithMain, resourcePath).scoped { eval =>
        val Right(result) =
          eval.apply(HelloJavaWithMain.app.runMain(
            "hello.Main",
            "testArg"
          )).runtimeChecked
        assert(result.evalCount > 0)
      }
      test("notRunInvalidMainObject") - UnitTester(
        HelloJavaWithMain,
        resourcePath
      ).scoped { eval =>
        val Left(ExecResult.Failure(msg = "Subprocess failed")) =
          eval.apply(HelloJavaWithMain.app.runMain("Invalid")).runtimeChecked
      }
      test("notRunWhenCompileFailed") - UnitTester(
        HelloJavaWithMain,
        resourcePath
      ).scoped { eval =>
        os.write.append(
          HelloJavaWithMain.moduleDir / "app/src/Main.java",
          "invalid java syntax"
        )

        val Left(_: ExecResult.Failure[_]) =
          eval.apply(HelloJavaWithMain.app.runMain("hello.Main")).runtimeChecked

      }
    }

    test("forkRun") {
      test("runIfMainClassProvided") - UnitTester(HelloJavaWithMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(
          HelloJavaWithMain.app.run(Task.Anon(Args("testArg")))
        ).runtimeChecked

        assert(result.evalCount > 0)
      }

      test("runUsesInteractiveSubprocess") - UnitTester(HelloJavaWithMain, resourcePath).scoped {
        eval =>
          var seen: Option[LauncherSubprocess.Config] = None

          LauncherSubprocess.withValue(config => { seen = Some(config); 0 }) {
            val Right(result) =
              eval.apply(HelloJavaWithMain.app.run(Task.Anon(Args("testArg")))).runtimeChecked
            assert(result.evalCount > 0)
          }

          assert(seen.nonEmpty)
      }
      test("notRunWithoutMainClass") - UnitTester(
        HelloJavaWithoutMain,
        sourceRoot = noMainResourcePath
      ).scoped { eval =>
        val Left(_: ExecResult.Failure[_]) =
          eval.apply(HelloJavaWithoutMain.app.run()).runtimeChecked
      }

      test("allLocalMainClasses") - UnitTester(HelloJavaDefaultMain, resourcePath).scoped {
        eval =>
          val Right(result) =
            eval.apply(HelloJavaDefaultMain.app.allLocalMainClasses).runtimeChecked

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
          ).runtimeChecked

          assert(result.evalCount > 0)
      }
    }

    test("run") {
      test("runIfMainClassProvided") - UnitTester(HelloJavaWithMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(
          HelloJavaWithMain.app.runLocal(Task.Anon(Args("testArg")))
        ).runtimeChecked

        assert(result.evalCount > 0)
      }
      test("runWithDefaultMain") - UnitTester(HelloJavaDefaultMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(
          HelloJavaDefaultMain.app.runLocal(Task.Anon(Args("testArg")))
        ).runtimeChecked

        assert(result.evalCount > 0)
      }
      test("notRunWithoutMainClass") - UnitTester(
        HelloJavaWithoutMain,
        sourceRoot = noMainResourcePath
      ).scoped { eval =>
        val Left(_: ExecResult.Failure[_]) =
          eval.apply(HelloJavaWithoutMain.app.runLocal()).runtimeChecked
      }
    }
  }
}
