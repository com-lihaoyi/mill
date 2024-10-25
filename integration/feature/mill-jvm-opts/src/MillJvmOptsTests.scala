package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object MillJvmOptsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester._
      val res = eval("checkJvmOpts")
      assert(res.isSuccess)
    }
    test("interpolatedEnvVars") - integrationTest { tester =>
      import tester._
      val res = eval("checkEnvJvmOpts")
      assert(res.isSuccess)
    }
    test("nonJvmOpts") - integrationTest { tester =>
      import tester._
      val res = eval("checkNonJvmOpts")
      assert(res.isSuccess)
    }
  }
}
