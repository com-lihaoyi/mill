package mill
package kotlinlib
package js

import mill.api.ExecResult
import mill.api.Discover
import mill.api.ExecutionPaths
import mill.testkit.{TestRootModule, UnitTester}
import utest.{TestSuite, Tests, test, assert}

object KotlinJsKotlinTestPackageModuleTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"

  private val kotlinVersion = "1.9.25"

  object module extends TestRootModule {

    object bar extends KotlinJsModule {
      def kotlinVersion = KotlinJsKotlinTestPackageModuleTests.kotlinVersion
    }

    object foo extends KotlinJsModule {
      def kotlinVersion = KotlinJsKotlinTestPackageModuleTests.kotlinVersion
      override def kotlinJsRunTarget = Some(RunTarget.Node)
      override def moduleDeps = Seq(module.bar)

      object test extends KotlinJsModule with KotlinTestPackageTests {
        override def allSourceFiles = super.allSourceFiles()
          .filter(!_.path.toString().endsWith("HelloKotestTests.kt"))
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {

    test("run tests") {
      testEval().scoped { eval =>

        val command = module.foo.test.testForked()
        val Left(ExecResult.Failure(msg = failureMessage)) =
          eval.apply(command): @unchecked

        val xmlReport =
          ExecutionPaths.resolve(eval.outPath, command).dest / "test-report.xml"

        assert(
          os.exists(xmlReport),
          os.read(xmlReport).contains("HelloKotlinTestPackageTests.kt:"),
          failureMessage ==
            s"""
               |Tests failed:
               |
               |foo HelloTests - failure: AssertionError: Expected <Hello, world>, actual <Not hello, world>.
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

}
