package mill.scalalib

import mill.testkit.UnitTester
import sbt.testing.Status
import utest._

object TestRunnerZiotestTests extends TestSuite {
  import TestRunnerTestUtils._
  override def tests: Tests = Tests {
    test("test") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval(testrunner.ziotest.test())
      assert(result.value._2.size == 1)
      junitReportIn(eval.outPath, "ziotest").shouldHave(1 -> Status.Success)
    }
    test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(testrunner.ziotest.discoveredTestClasses)
      val expected = Seq("mill.scalalib.ZioTestSpec")
      assert(result.value == expected)
      expected
    }
  }

}
