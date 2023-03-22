package mill.integration

import utest._

class HygieneTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("hygiene", fork, clientServer) {
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
