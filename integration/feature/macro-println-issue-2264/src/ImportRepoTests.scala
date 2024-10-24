package mill.integration

import utest._

object ImportRepoTests extends mill.testkit.UtestIntegrationTestSuite {
  val tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      println("First run")
      assert(eval("sandbox.run"))
      println("Clean compilation")
      assert(eval("clean", "sandbox.compile"))
      println("Second run")
      assert(eval("sandbox.run"))
    }
  }
}
