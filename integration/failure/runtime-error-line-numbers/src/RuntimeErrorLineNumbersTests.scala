package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object RuntimeErrorLineNumbersTests extends UtestIntegrationTestSuite {
  def captureOutErr = true
  val tests: Tests = Tests {
    test("resolve") - integrationTest { tester =>
      // Make exceptions point at the correct line numbers in the
      // build.mill, package.mill, and utility files
      val res1 = tester.eval("rootBoom")
      assert(!res1.isSuccess)
      assert(res1.err.contains("(build.mill:5)"))

      val res2 = tester.eval("rootUtilBoom")
      assert(!res2.isSuccess)
      assert(res2.err.contains("(build.mill:6)"))
      assert(res2.err.contains("(util.mill:4)"))

      val res3 = tester.eval("foo.fooBoom")
      assert(!res3.isSuccess)
      assert(res3.err.contains("(package.mill:5)"))

      val res4 = tester.eval("foo.fooVersionsBoom")
      assert(!res4.isSuccess)
      assert(res4.err.contains("(versions.mill:3)"))
      assert(res4.err.contains("(package.mill:6)"))
    }
  }
}
