package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object YamlConfigTasksTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval(("-k", "__.compile"))

      assert(!res.isSuccess)

      res.assertContainsLines(
        "[error] build.mill.yaml:2:1",
        "scalaVersionn: []",
        "^"
      )
      assert(res.err.contains(
        "key \"scalaVersionn\" does not override any task on ScalaModule, did you mean \"scalaVersion\"?"
      ))
      res.assertContainsLines(
        "[error] test/package.mill.yaml:2:1",
        "scalaVersionWrongInner: []",
        "^"
      )
      assert(res.err.contains("key \"scalaVersionWrongInner\" does not override any task"))

      tester.modifyFile(
        tester.workspacePath / "build.mill.yaml",
        _.replace("scalaVersionn", "scalaVersion")
      )
      tester.modifyFile(
        tester.workspacePath / "test/package.mill.yaml",
        _.replace("scalaVersionWrongInner", "scalaVersion")
      )

      val res2 = tester.eval(("-k", "__.compile"))

      res2.assertContainsLines(
        "[error] build.mill.yaml:2:15",
        "scalaVersion: []",
        "              ^"
      )
      assert(
        res2.err.contains("Failed de-serializing config override: expected string got sequence")
      )
      res2.assertContainsLines(
        "[error] test/package.mill.yaml:2:15",
        "scalaVersion: []",
        "              ^"
      )

      tester.modifyFile(
        tester.workspacePath / "build.mill.yaml",
        _.replace("scalaVersion", "#scalaVersion")
      )
      tester.modifyFile(
        tester.workspacePath / "test/package.mill.yaml",
        _.replace("scalaVersion", "#scalaVersion")
      )

      val res3 = tester.eval(("-k", "__.compile"))

      assert(res3.err.contains("scalaVersion configuration missing in build.mill.yaml"))
      assert(res3.err.contains("test.scalaVersion configuration missing in test/package.mill.yaml"))
    }
  }
}
