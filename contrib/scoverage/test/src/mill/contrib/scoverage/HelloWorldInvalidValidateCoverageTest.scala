package mill.contrib.scoverage

import mill.*
import mill.api.Discover
import mill.api.daemon.ExecResult.Failure
import mill.contrib.buildinfo.BuildInfo
import mill.scalalib.{DepSyntax, ScalaModule, TestModule}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

trait HelloWorldInvalidValidateCoverageTest extends utest.TestSuite {
  def testScalaVersion: String
  def testScoverageVersion: String
  def testScalatestVersion: String = "3.2.13"

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world"

  object InvalidCoverageCheck extends TestRootModule {
    object core extends ScoverageModule with BuildInfo {
      def scalaVersion = testScalaVersion
      def scoverageVersion = testScoverageVersion

      // Intentionally leave coverage minimums empty
      def statementCoverageMin = None
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
    test("HelloWorldInvalidValidateCoverageTest") {
      test("core") {
        test("validateCoverageMinimums fails with no minimums") - UnitTester(
          InvalidCoverageCheck,
          resourcePath
        ).scoped { eval =>
          val Left(Failure(msg = msg)) =
            eval.apply(InvalidCoverageCheck.core.scoverage.validateCoverageMinimums()): @unchecked

          assert(
            msg.equals(
              "Either statementCoverageMin or branchCoverageMin must be set in order to call the validateCoverageMinimums task."
            )
          )
        }
      }
    }
  }
}

object HelloWorldInvalidValidateCoverageTest_2_13 extends HelloWorldInvalidValidateCoverageTest {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)
}

object HelloWorldInvalidValidateCoverageTest_3_2 extends HelloWorldInvalidValidateCoverageTest {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_3_2_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)
}
