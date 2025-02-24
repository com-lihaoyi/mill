package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object NoJavaBootstrapTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      os.remove(tester.workspacePath / ".mill-jvm-version")

      val out = eval("foo")
      assert(out == "17.0.3")
    }
  }
}
