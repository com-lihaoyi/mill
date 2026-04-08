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
      res.assertContainsLines(
        "[error] badnestedname/package.mill.yaml:4:1",
        "object typo:",
        "^",
        """Config key "object typo" does not match any nested module in millbuild.MyModule"""
      )
    }
    test("mismatchedNestedConfigKey") - integrationTest { tester =>
      import tester.*
      // `badnestedkey/package.mill.yaml` declares `object test:` (which exists)
      // but sets `mvnDep:` (singular) instead of `mvnDeps:` (plural).
      // This should produce an error about the invalid config key.
      val res = eval("badnestedkey.test.compile")
      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] badnestedkey/package.mill.yaml:5:3",
        "  mvnDep: org.hamcrest:hamcrest:2.2",
        "  ^",
        """key "mvnDep" does not override any task on millbuild.MyModule, did you mean "mvnDeps"?"""
      )
    }
    test("selfReferentialModuleDeps") - integrationTest { tester =>
      import tester.*
      // `selfref/package.mill.yaml` declares `moduleDeps: [selfref]`, referencing
      // itself. This should produce a proper error about circular deps rather than
      // silently deadlocking.
      val res = eval("selfref.compile")
      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] selfref/package.mill.yaml:3:14",
        "moduleDeps: [selfref]",
        "             ^",
        "Circular moduleDeps detected: precompiled module 'selfref' depends on itself"
      )
    }
  }
}
