package mill
package kotlinlib
package js

import mill.eval.EvaluatorPaths
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{assert, TestSuite, Tests, test}

object KotlinJSKotlinTestPackageModuleTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"

  private val kotlinVersion = "1.9.25"

  object module extends TestBaseModule {

    object bar extends KotlinJSModule {
      def kotlinVersion = KotlinJSKotlinTestPackageModuleTests.kotlinVersion
    }

    object foo extends KotlinJSModule {
      def kotlinVersion = KotlinJSKotlinTestPackageModuleTests.kotlinVersion
      override def moduleDeps = Seq(module.bar)

      object test extends KotlinJSModule with KotlinTestPackageTests {
        override def allSourceFiles = super.allSourceFiles()
          .filter(!_.path.toString().endsWith("HelloKotestTests.kt"))
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
        log.contains("AssertionError: Expected <Hello, world>, actual <Not hello, world>."),
        log.contains("1 passing"),
        log.contains("1 failing"),
        // verify that source map is applied, otherwise all stack entries will point to .js
        log.contains("HelloKotlinTestPackageTests.kt:")
      )
    }
  }

}
