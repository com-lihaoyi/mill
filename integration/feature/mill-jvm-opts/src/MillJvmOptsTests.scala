package mill.integration

import mill.client.Util
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
      if (!Util.isWindows) { // PWD does not exist on windows
        import tester._
        val res = eval(("show", "getEnvJvmOpts"))
        val out = res.out
        val expected = "\"value-with-" + tester.workspacePath + "\""
        assert(out == expected)
      }
    }
    test("alternate") - integrationTest { tester =>
      if (!Util.isWindows) {
        import tester._
        val res = eval(
          ("show", "getEnvJvmOpts"),
          env = Map("MILL_JVM_OPTS_PATH" -> ".mill-jvm-opts-alternate")
        )
        assert(res.out == "\"alternate-value-with-" + tester.workspacePath + "\"")
      }
    }
    test("nonJvmOpts") - integrationTest { tester =>
      import tester._
      val res = eval(("show", "getNonJvmOpts"))
      assert(res.out == "17")
    }
    test("overrideNonJvmOpts") - integrationTest { tester =>
      import tester._
      val res = eval(("--jobs", "19", "show", "getNonJvmOpts"))
      assert(res.out == "19")
    }
    test("nonJvmOptsAlternate") - integrationTest { tester =>
      import tester._
      val res =
        eval(("show", "getNonJvmOpts"), env = Map("MILL_OPTS_PATH" -> ".mill-opts-alternate"))
      assert(res.out == "29")
    }
  }
}
