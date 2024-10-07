package mill
package kotlinlib
package js

import mill.eval.EvaluatorPaths
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, test}

object KotlinJSTestModuleTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"

  private val kotlinVersion = "1.9.25"

  object module extends TestBaseModule {

    object bar extends KotlinJSModule {
      def kotlinVersion = KotlinJSTestModuleTests.kotlinVersion
    }

    object foo extends KotlinJSModule {
      def kotlinVersion = KotlinJSTestModuleTests.kotlinVersion
      override def moduleDeps = Seq(module.bar)

      object test extends KotlinJSModule with KotlinJSKotlinXTests
    }
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {

    test("run tests") {
      val eval = testEval()

      val command = module.foo.test.test()
      val Left(_) = eval.apply(command)

      // temporary, because we are running run() task, it won't be test.log, but run.log
      val log = EvaluatorPaths.resolveDestPaths(eval.outPath, command).log / ".." / "run.log"
      assert(
        os.read(log).contains("AssertionError: Expected <Hello, world>, actual <Not hello, world>.")
      )
    }
  }

}
