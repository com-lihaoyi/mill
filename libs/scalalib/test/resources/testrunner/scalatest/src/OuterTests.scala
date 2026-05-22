// Issue https://github.com/com-lihaoyi/mill/issues/7094
package mill.scalalib

import org.scalatest.Args
import org.scalatest.funsuite.AnyFunSuite

class OuterTests extends AnyFunSuite {
  test("the test") {
    class InnerTests extends AnyFunSuite {
      test("inner test")(assert(2 + 2 == 5))
    }

    val suite = new InnerTests()
    assert(!suite.run(None, Args(_ => ())).succeeds())
  }
}
