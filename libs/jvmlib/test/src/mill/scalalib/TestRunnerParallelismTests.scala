package mill.scalalib

import mill.T
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.util.TokenReaders.*

object TestRunnerParallelismTests extends TestSuite {
  object utestSingleTest extends TestRootModule with ScalaModule with TestModule.Utest {
    override def scalaVersion: T[String] = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    override def utestVersion = sys.props.getOrElse("TEST_UTEST_VERSION", ???)

    override def testParallelism = true

    lazy val millDiscover = Discover[this.type]
  }

  override def tests: Tests = Tests {
    test("single group with single test") - UnitTester(
      utestSingleTest,
      os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "utestSingleTest"
    ).scoped { eval =>
      val Right(result) = eval.apply(utestSingleTest.testForked()): @unchecked
      val (doneMsg @ _, results) = result.value
      // Only one test should have been run
      assert(results.size == 1)
      // If we are actually running in serial mode (because there was only 1 group and 1 test case, so we disabled
      // parallelism) there should be no 'worker-X' directories and the 'sandbox' should be at the top level.
      assert(os.exists(eval.outPath / "testForked.dest" / "sandbox"))
    }
  }

}
