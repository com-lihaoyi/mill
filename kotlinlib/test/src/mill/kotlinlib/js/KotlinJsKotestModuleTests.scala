package mill
package kotlinlib.js

import mill.api.ExecResult
import mill.define.Discover
import mill.exec.ExecutionPaths
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, assert, test}

object KotlinJsKotestModuleTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"

  private val kotlinVersion = "1.9.25"

  object module extends TestBaseModule {

    object bar extends KotlinJsModule {
      def kotlinVersion = KotlinJsKotestModuleTests.kotlinVersion
    }

    object foo extends KotlinJsModule {
      def kotlinVersion = KotlinJsKotestModuleTests.kotlinVersion
      override def kotlinJsRunTarget = Some(RunTarget.Node)
      override def moduleDeps = Seq(module.bar)

      object test extends KotlinJsModule with KotestTests {
        override def allSourceFiles = super.allSourceFiles()
          .filter(!_.path.toString().endsWith("HelloKotlinTestPackageTests.kt"))
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {

    test("run tests") {
      val eval = testEval()

      val command = module.foo.test.testForked()
      val Left(ExecResult.Failure(failureMessage)) =
        eval.apply(command): @unchecked

      val xmlReport =
        ExecutionPaths.resolveDestPaths(eval.outPath, command).dest / "test-report.xml"

      assert(
        os.exists(xmlReport),
        os.read(xmlReport).contains("HelloKotestTests.kt:"),
        failureMessage == s"""
                             |Tests failed:
                             |
                             |HelloTests - failure: AssertionFailedError: expected:<\"Not hello, world\"> but was:<\"Hello, world\">
                             |
                             |""".stripMargin
//        doneMessage == s"""
//                          |Tests: 2, Passed: 1, Failed: 1, Skipped: 0
//                          |
//                          |Full report is available at $xmlReport
//                          |""".stripMargin,
//        testResults.length == 2,
//        testResults.count(result =>
//          result.status == Status.Failure.name() && result.exceptionTrace.getOrElse(
//            Seq.empty
//          ).isEmpty
//        ) == 0
      )
    }
  }

}
