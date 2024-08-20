package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object CrossCollisionsTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("detect-collision") {
      val res = eval(("resolve", "foo._"))
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
