package mill.integration

import utest._

object CrossCollisionsTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("detect-collision") {
      val res = evalStdout("resolve", "foo._")
      assert(!res.isSuccess)
      assert(
        res.err.contains(
          "Cross module millbuild.build#foo contains colliding cross values: List(List(a, b), List(c)) and List(List(a), List(b, c))"
        )
      )
    }
  }
}
