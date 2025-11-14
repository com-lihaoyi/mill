package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlConfigTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval(("-k", "__.compile"))

      assert(!res.isSuccess)

      assert(res.err.contains("invalid build config in `build.mill.yaml`: key \"scalaVersionWrong\" does not override any task"))
      assert(res.err.contains("invalid build config in `test/package.mill.yaml`: key \"scalaVersionWrongInner\" does not override any task"))

      tester.modifyFile(tester.workspacePath / "build.mill.yaml", _.replace("scalaVersionWrong", "scalaVersion"))
      tester.modifyFile(tester.workspacePath / "test/package.mill.yaml", _.replace("scalaVersionWrongInner", "scalaVersion"))

      val res2 = tester.eval(("-k", "__.compile"))

      assert(res2.err.contains("scalaVersion Failed de-serializing config override: expected string got sequence"))
      assert(res2.err.contains("test.scalaVersion Failed de-serializing config override: expected string got sequence"))

      tester.modifyFile(tester.workspacePath / "build.mill.yaml", _.replace("scalaVersion", "#scalaVersion"))
      tester.modifyFile(tester.workspacePath / "test/package.mill.yaml", _.replace("scalaVersion", "#scalaVersion"))

      val res3 = tester.eval(("-k", "__.compile"))

      assert(res3.err.contains("scalaVersion Not Implemented"))
      assert(res3.err.contains("test.scalaVersion Not Implemented"))
    }
  }
}
