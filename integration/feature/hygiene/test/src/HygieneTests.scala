package mill.integration

import utest._

object HygieneTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test {
      val res = eval("scala.foo")
      assert(res == true)
      val output = meta("scala.foo")
      assert(output.contains("\"fooValue\""))
    }
  }
}
