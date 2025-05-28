package mill.integration

import mill.constants.Util
import mill.testkit.UtestIntegrationTestSuite
import utest._

object MillJvmPropertyTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester._
      // Property not set
      val res1 = eval(("printSystemProperty", "--propName", "foo-property"))
      assert(res1.out == "null")

      // Property newly set
      val res2 =
        eval(("-Dfoo-property=hello-world", "printSystemProperty", "--propName", "foo-property"))
      assert(res2.out == "hello-world")

      // Existing property modified
      val res3 =
        eval(("-Dfoo-property=i-am-cow", "printSystemProperty", "--propName", "foo-property"))
      assert(res3.out == "i-am-cow")

      // Existing property removed
      val res4 = eval(("printSystemProperty", "--propName", "foo-property"))
      assert(res4.out == "null")
    }
  }
}
