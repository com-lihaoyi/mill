package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object PrecompiledNestedConfigMismatchTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("mismatchedNestedObjectName") - integrationTest { tester =>
      import tester.*
      // `badnestedname/package.mill.yaml` declares `object typo:` but the
      // precompiled class `MyModule` only has `object test`. Accessing the
      // module should produce an error about the unmatched nested object name.
      val res = eval("badnestedname.compile")
      assert(res.isSuccess == false)
      assert(res.err.contains("does not match any nested module"))
      assert(res.err.contains("\"object typo\""))
    }
    test("mismatchedNestedConfigKey") - integrationTest { tester =>
      import tester.*
      // `badnestedkey/package.mill.yaml` declares `object test:` (which exists)
      // but sets `mvnDep:` (singular) instead of `mvnDeps:` (plural).
      // This should produce an error about the invalid config key.
      val res = eval("badnestedkey.test.compile")
      assert(res.isSuccess == false)
      assert(res.err.contains("mvnDep"))
      assert(res.err.contains("does not override any task"))
    }
  }
}
