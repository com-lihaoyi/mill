package mill.integration

import utest._

object ImportRepoTests extends IntegrationTestSuite {
  val tests = Tests {
    initWorkspace()
    test("test") - {
      println("First run")
      assert(eval("sandbox.run"))
      println("Clean compilation")
      assert(eval("clean", "sandbox.compile"))
      println("Second run")
      assert(eval("sandbox.run"))
    }
  }
}
