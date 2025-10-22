package mill.contrib.scoverage

import mill._
import mill.api.ExecResult
import mill.contrib.buildinfo.BuildInfo
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

trait HelloWorldInvalidValidateCoverageTest extends utest.TestSuite {
  def testScalaVersion: String
  def testScoverageVersion: String

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world"

  object InvalidCoverageCheck extends TestRootModule {
    object core extends ScoverageModule with BuildInfo {
      def scalaVersion = testScalaVersion
      def scoverageVersion = testScoverageVersion
      
      // Intentionally leave coverage minimums empty
      def statementCoverageMin = None
      def branchCoverageMin = None

      override def moduleDeps = Seq.empty
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: utest.Tests = utest.Tests {
    test("HelloWorldInvalidValidateCoverageTest") {
      test("core") {
        test("validateCoverageMinimums fails with no minimums") - UnitTester(InvalidCoverageCheck, resourcePath).scoped { eval =>
          val Left(ExecResult.Failure(msg)) =
            eval.apply(InvalidCoverageCheck.core.scoverage.validateCoverageMinimums): @unchecked
          assert(
            msg.contains("No coverage minimums defined"),
            "validateCoverageMinimums should fail when no minimums are provided"
          )
        }
      }
    }
  }
}

object HelloWorldInvalidValidateCoverageTest extends HelloWorldInvalidValidateCoverageTest {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE_VERSION", ???)
}
