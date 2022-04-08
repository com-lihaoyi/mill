package mill.integration

import mill.util.ScriptTestSuite
import utest._

class HygieneTests(fork: Boolean) extends IntegrationTestSuite("hygiene", fork) {
  val tests = Tests {
    initWorkspace()

    test {
      val res = eval("scala.foo")
      assert(res == true)
      val output = meta("scala.foo")
      assert(output.contains("\"fooValue\""))
    }
  }
}
