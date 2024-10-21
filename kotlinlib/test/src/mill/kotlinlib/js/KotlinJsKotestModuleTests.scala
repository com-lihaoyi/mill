package mill
package kotlinlib.js

import mill.eval.EvaluatorPaths
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{assert, TestSuite, Tests, test}

object KotlinJsKotestModuleTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"

  private val kotlinVersion = "1.9.25"

  object module extends TestBaseModule {

    object bar extends KotlinJsModule {
      def kotlinVersion = KotlinJsKotestModuleTests.kotlinVersion
    }

    object foo extends KotlinJsModule {
      def kotlinVersion = KotlinJsKotestModuleTests.kotlinVersion
      override def moduleDeps = Seq(module.bar)

      object test extends KotlinJsModule with KotestTests {
        override def allSourceFiles = super.allSourceFiles()
          .filter(!_.path.toString().endsWith("HelloKotlinTestPackageTests.kt"))
      }
    }
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {

    test("run tests") {
      val eval = testEval()

      val command = module.foo.test.test()
      val Left(_) = eval.apply(command)

      // temporary, because we are running run() task, it won't be test.log, but run.log
      val log =
        os.read(EvaluatorPaths.resolveDestPaths(eval.outPath, command).log / ".." / "run.log")
      assert(
        log.contains(
          "AssertionFailedError: expected:<\"Not hello, world\"> but was:<\"Hello, world\">"
        ),
        log.contains("1 passing"),
        log.contains("1 failing"),
        // verify that source map is applied, otherwise all stack entries will point to .js
        log.contains("HelloKotestTests.kt:")
      )
    }
  }

}
