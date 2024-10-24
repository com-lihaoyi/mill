package mill.integration

import utest._

object ImportRepoTests extends mill.testkit.UtestIntegrationTestSuite {
  val tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      println("First run")
      assert(eval("sandbox.run", stdout = os.Inherit).isSuccess)
      println("Clean compilation")
      assert(eval(("clean", "sandbox.compile"), stdout = os.Inherit).isSuccess)
      println("Second run")
      assert(eval("sandbox.run", stdout = os.Inherit).isSuccess)
    }
  }
}
