package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

object MillInitTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("Mill init works") - integrationTest { tester =>
      import tester._
      val res = eval("init")
      res.isSuccess ==> true

      val exampleListOut = out("init")
      val parsed = exampleListOut.json.arr.map(_.str)
      assert(parsed.nonEmpty)
      assert(res.out.startsWith(
        "Run init with one of the following examples as an argument to download and extract example:"
      ))

    }
  }
}
