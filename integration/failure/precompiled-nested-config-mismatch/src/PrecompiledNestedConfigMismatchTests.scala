package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object PrecompiledNestedConfigMismatchTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("mismatchedNestedObjectName") - integrationTest { tester =>
      import tester.*
      // `badnestedname/package.mill.yaml` declares `object typo:` but the
      // precompiled class `MyModule` only has `object test`.
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
      // itself. Should produce a proper error rather than deadlocking.
      val res = eval("selfref.compile")
      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] selfref/package.mill.yaml:3:14",
        "moduleDeps: [selfref]",
        "             ^",
        "Circular moduleDeps detected: precompiled module 'selfref' depends on itself"
      )
    }
    test("indirectCircularModuleDeps") - integrationTest { tester =>
      import tester.*
      // `circularA` depends on `circularB` and `circularB` depends on `circularA`.
      // Should produce a proper error rather than deadlocking.
      val res = eval("circularA.compile")
      assert(res.isSuccess == false)
      assert(
        res.err.contains("Circular") || res.err.contains("Recursive") ||
          res.err.contains("circular") || res.err.contains("recursive")
      )
    }
    test("badExtendsClass") - integrationTest { tester =>
      import tester.*
      // A precompiled module referencing a non-existent class should produce
      // a compile error from the generated code.
      os.makeDir.all(tester.workspacePath / "badextendsclass")
      os.write.over(
        tester.workspacePath / "badextendsclass/package.mill.yaml",
        "extends: millbuild.DoesNotExist\nmill-experimental-precompiled-module: true\n"
      )
      val res = eval("badextendsclass.compile")
      assert(res.isSuccess == false)
      res.assertContainsLines(
        "type DoesNotExist is not a member of millbuild"
      )
      os.remove.all(tester.workspacePath / "badextendsclass")
    }
    test("wrongConstructorSignature") - integrationTest { tester =>
      import tester.*
      // A precompiled module extending a class whose constructor doesn't take
      // PrecompiledModule.Config should produce a runtime error on instantiation.
      os.makeDir.all(tester.workspacePath / "wrongsig")
      os.write.over(
        tester.workspacePath / "wrongsig/package.mill.yaml",
        "extends: millbuild.WrongSigModule\nmill-experimental-precompiled-module: true\n"
      )
      val res = eval("wrongsig.compile")
      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] wrongsig/package.mill.yaml:1:10",
        "extends: millbuild.WrongSigModule",
        "         ^",
        "Precompiled module 'wrongsig/package.mill.yaml' extends 'millbuild.WrongSigModule' which does not have a (val scriptConfig: mill.api.PrecompiledModule.Config) constructor parameter. Found constructor(s): (String)"
      )
      os.remove.all(tester.workspacePath / "wrongsig")
    }
  }
}
