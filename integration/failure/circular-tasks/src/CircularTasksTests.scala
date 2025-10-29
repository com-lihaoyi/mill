package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object CircularTasksTests extends UtestIntegrationTestSuite {
  def captureOutErr = true
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      // Ensure that resolve works even of the modules containing the resolved
      // tasks are broken
      val res = tester.eval(("module.nested.taskA"))
      assert(res.err.contains(
        """Circular task dependency detected:
          |module.nested.taskA
          |depends on: module.taskB
          |depends on: taskC
          |depends on: module.nested.taskA""".stripMargin.replace("\r\n", "\n")
      ))
    }
  }
}
