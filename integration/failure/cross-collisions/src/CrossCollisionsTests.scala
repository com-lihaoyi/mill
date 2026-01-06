package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object CrossCollisionsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("detect-collision") - integrationTest { tester =>
      val res = tester.eval(("resolve", "foo._"))
      assert(!res.isSuccess)
      assert(res.err.contains("Cross module "))
      assert(
        res.err.contains(
          " contains colliding cross values: List(List(a, b), List(c)) and List(List(a), List(b, c))"
        )
      )
    }
  }
}
