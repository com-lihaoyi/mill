package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

// Basic tests for errors in build.mill.yaml an package.mill.yaml files.
object YamlConfigMiscTests extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    integrationTest { tester =>
      // This errors doesn't look great, but for now just assert on them and make sure they don't regress
      val res = tester.eval(("resolve", "_"))
      assert(res.err.replace('\\', '/').contains("mispelledextends/package.mill.yaml"))
      assert(res.err.contains("trait package_ extends mill.javalib.JavaModuleTypod"))
      assert(res.err.contains("type JavaModuleTypod is not a member of mill.javalib"))

      tester.modifyFile(
        tester.workspacePath / "mispelledextends/package.mill.yaml",
        _ => "mill-version: 1.0.0\nextends: [mill.javalib.JavaModule]"
      )

      val res2 = tester.eval("mispelledextends.compile")
      assert(!res2.isSuccess)
      res2.assertContainsLines(
        "[error] mispelledextends/package.mill.yaml:1:1",
        "mill-version: 1.0.0",
        "^"
      )
      assert(res2.err.contains(
        "key \"mill-version\" can only be used in your root `build.mill` or `build.mill.yaml` file"
      ))

      tester.modifyFile(
        tester.workspacePath / "mispelledextends/package.mill.yaml",
        _ => "objec lols:\n"
      )

      val res3 = tester.eval("mispelledextends.compile")
      assert(!res3.isSuccess)
      res3.assertContainsLines(
        "[error] mispelledextends/package.mill.yaml:1:1",
        "objec lols:",
        "^"
      )
      assert(res3.err.contains("generatedScriptSources Invalid key: objec lols"))

      // Fix the mispelledextends file before testing badmoduledep
      tester.modifyFile(
        tester.workspacePath / "mispelledextends/package.mill.yaml",
        _ => "extends: [mill.javalib.JavaModule]"
      )

      // Test invalid moduleDeps reference produces error with file and line number
      val res4 = tester.eval("badmoduledep.compile")
      assert(!res4.isSuccess)
      res4.assertContainsLines(
        "[error] badmoduledep/package.mill.yaml:2:14",
        "moduleDeps: [doesnotexist]",
        "             ^"
      )
      assert(res4.err.contains("Cannot resolve moduleDep 'doesnotexist'"))

      // Test that a JavaModule depending on a plain Module produces a type error
      val res5 = tester.eval("wrongmoduledeptype.compile")
      assert(!res5.isSuccess)
      res5.assertContainsLines(
        "[error] wrongmoduledeptype/package.mill.yaml:2:14",
        "moduleDeps: [plainmodule]",
        "             ^"
      )
      assert(res5.err.contains("Module 'plainmodule' is a"))
      assert(res5.err.contains("not a"))
      assert(res5.err.contains("JavaModule"))

      // Test invalid YAML type for moduleDeps (missing `-`) reports file + line number
      os.makeDir.all(tester.workspacePath / "badmoduledepsformat")
      os.write.over(
        tester.workspacePath / "badmoduledepsformat/package.mill.yaml",
        """extends: [mill.javalib.JavaModule]
          |object test:
          |  extends: [mill.javalib.JavaModule]
          |  moduleDeps:
          |    otherProject
          |""".stripMargin
      )
      val res6 = tester.eval("badmoduledepsformat.test.compile")
      assert(!res6.isSuccess)
      res6.assertContainsLines(
        "[error] badmoduledepsformat/package.mill.yaml:5:5",
        "    otherProject",
        "    ^"
      )
      assert(res6.err.contains("expected sequence got string"))

      // Test invalid YAML type for top-level moduleDeps reports file + line number
      os.remove.all(tester.workspacePath / "badmoduledepsformat")
      os.makeDir.all(tester.workspacePath / "badtopmoduledepsformat")
      os.write.over(
        tester.workspacePath / "badtopmoduledepsformat/package.mill.yaml",
        """extends: [mill.javalib.JavaModule]
          |moduleDeps:
          |  otherProject
          |""".stripMargin
      )
      val res7 = tester.eval("badtopmoduledepsformat.compile")
      assert(!res7.isSuccess)
      res7.assertContainsLines(
        "[error] badtopmoduledepsformat/package.mill.yaml:3:3",
        "  otherProject",
        "  ^"
      )
      assert(res7.err.contains("expected sequence got string"))
    }
  }
}
