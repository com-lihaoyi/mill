package mill
package kotlinlib
package js

import mill.api.Discover
import mill.api.ExecutionPaths
import mill.testkit.{TestRootModule, UnitTester}
import utest.{TestSuite, Tests, test}

object KotlinJsNodeRunTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"
  private val kotlinVersion = "1.9.25"
  private val expectedSuccessOutput = "Hello, world"

  object module extends TestRootModule {

    private val matrix = for {
      splits <- Seq(true, false)
      modules <- Seq("no", "plain", "es", "amd", "commonjs", "umd")
    } yield (splits, modules)

    trait KotlinJsModuleKindCross extends KotlinJsModule with Cross.Module2[Boolean, String] {

      def kotlinVersion = KotlinJsNodeRunTests.kotlinVersion

      override def kotlinJsModuleKind = crossValue2 match {
        case "no" => ModuleKind.NoModule
        case "plain" => ModuleKind.PlainModule
        case "es" => ModuleKind.ESModule
        case "amd" => ModuleKind.AMDModule
        case "commonjs" => ModuleKind.CommonJSModule
        case "umd" => ModuleKind.UMDModule
      }

      override def moduleDeps = Seq(module.bar)
      override def kotlinJsSplitPerModule = crossValue
      override def kotlinJsRunTarget = Some(RunTarget.Node)
    }

    object bar extends KotlinJsModule {
      def kotlinVersion = KotlinJsNodeRunTests.kotlinVersion
    }

    object foo extends Cross[KotlinJsModuleKindCross](matrix)

    lazy val millDiscover = Discover[this.type]
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {
    // region with split per module

    test("split - plain module") {
      testEval().scoped { eval =>

        // plain modules cannot handle the dependencies, so if there are multiple js files, it will fail
        val Left(_) = eval.apply(module.foo(true, "plain").run()): @unchecked
      }
    }

    test("split - es module") {
      testEval().scoped { eval =>

        val command = module.foo(true, "es").run()
        val Right(_) = eval.apply(command): @unchecked

        assertLogContains(eval, command, expectedSuccessOutput)
      }
    }

    test("split - amd module") {
      testEval().scoped { eval =>

        // amd modules have "define" method, it is not known by Node.js
        val Left(_) = eval.apply(module.foo(true, "amd").run()): @unchecked
      }
    }

    test("split - commonjs module") {
      testEval().scoped { eval =>

        val command = module.foo(true, "commonjs").run()
        val Right(_) = eval.apply(command): @unchecked

        assertLogContains(eval, command, expectedSuccessOutput)
      }
    }
    test("split - umd module") {
      testEval().scoped { eval =>

        val command = module.foo(true, "umd").run()
        val Right(_) = eval.apply(command): @unchecked

        assertLogContains(eval, command, expectedSuccessOutput)
      }
    }
    test("split - no module") {
      testEval().scoped { eval =>

        val Left(_) = eval.apply(module.foo(true, "no").run()): @unchecked
      }
    }

    // endregion

    // region without split per module

    test("no split - plain module") {
      testEval().scoped { eval =>

        val command = module.foo(false, "plain").run()
        val Right(_) = eval.apply(command): @unchecked

        assertLogContains(eval, command, expectedSuccessOutput)
      }
    }

    test("no split - es module") {
      testEval().scoped { eval =>

        val command = module.foo(false, "es").run()
        val Right(_) = eval.apply(command): @unchecked

        assertLogContains(eval, command, expectedSuccessOutput)
      }
    }

    test("no split - amd module") {
      testEval().scoped { eval =>

        // amd modules have "define" method, it is not known by Node.js
        val Left(_) = eval.apply(module.foo(false, "amd").run()): @unchecked
      }
    }

    test("no split - commonjs module") {
      testEval().scoped { eval =>

        val command = module.foo(false, "commonjs").run()
        val Right(_) = eval.apply(command): @unchecked

        assertLogContains(eval, command, expectedSuccessOutput)
      }
    }
    test("no split - umd module") {
      testEval().scoped { eval =>

        val command = module.foo(false, "umd").run()
        val Right(_) = eval.apply(command): @unchecked

        assertLogContains(eval, command, expectedSuccessOutput)
      }
    }
    test("no split - no module") {
      testEval().scoped { eval =>

        val command = module.foo(false, "no").run()
        val Right(_) = eval.apply(command): @unchecked

        assertLogContains(eval, command, expectedSuccessOutput)
      }
    }

    // endregion
  }

  private def assertLogContains(eval: UnitTester, command: Command[Unit], text: String): Unit = {
    val log = ExecutionPaths.resolve(eval.outPath, command).log
    assert(os.read(log).contains(text))
  }

}
