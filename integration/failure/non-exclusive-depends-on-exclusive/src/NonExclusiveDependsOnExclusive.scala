package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object NonExclusiveDependsOnExclusive extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("wrong") - integrationTest { tester =>
      val res = tester.eval("cleanClientWrong")
      assert(res.isSuccess == false)
      assert(res.err.contains(
        "Non-Command task cleanClientWrong cannot depend on exclusive task clean"
      ))
      assert(!res.out.contains("cleanClientWrong done"))
    }
    test("right") - integrationTest { tester =>
      val res = tester.eval("cleanClientRight")
      assert(res.isSuccess == true)
      assert(res.out.contains("cleanClientRight done"))

    }
    test("downstream") - integrationTest { tester =>
      val res = tester.eval("cleanClientDownstream")
      assert(res.isSuccess == true)
      assert(res.out.contains("cleanClientDownstream done"))
    }
  }
}
