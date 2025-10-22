package mill.contrib.scoverage

import mill.*
import mill.api.Discover
import mill.api.daemon.ExecResult
import mill.contrib.buildinfo.BuildInfo
import mill.scalalib.{DepSyntax, ScalaModule, TestModule}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

trait HelloWorldStatementMinCoverageTest extends utest.TestSuite {
  def testScalaVersion: String
  def testScoverageVersion: String
  def testScalatestVersion: String = "3.2.13"

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world"

  object ValidCoverageCheck extends TestRootModule {
    object core extends ScoverageModule with BuildInfo {
      def scalaVersion = testScalaVersion
      def scoverageVersion = testScoverageVersion
      
      // Set statement coverage minimum to 15%
      def statementCoverageMin = Some(15.0)
      def branchCoverageMin = None

      override def moduleDeps = Seq.empty

      def buildInfoPackageName = "bar"
      override def buildInfoMembers = Seq(
        BuildInfo.Value("scoverageVersion", scoverageVersion())
      )
      object test extends ScoverageTests with TestModule.ScalaTest {
        override def mvnDeps = Seq(mvn"org.scalatest::scalatest:${testScalatestVersion}")
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: utest.Tests = utest.Tests {
    test("HelloWorldStatementMinCoverageTest") {
      test("core") {
        test("validateCoverageMinimums passes with statementCoverageMin set") - UnitTester(ValidCoverageCheck, resourcePath).scoped { eval =>
          val Right(result) = eval.apply(ValidCoverageCheck.core.scoverage.validateCoverageMinimums()): @unchecked

          // The task should pass since we've set the minimum coverage
          assert(result.evalCount > 0)
        }
      }
    }
  }
}

object HelloWorldStatementMinCoverageTest extends HelloWorldStatementMinCoverageTest {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE_VERSION", ???)
}
