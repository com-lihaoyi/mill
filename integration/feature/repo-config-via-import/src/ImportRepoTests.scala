package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ImportRepoTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      // Make sure, we properly parse a line:
      // ```
      //   import $repo.`file:///tmp/testrepo`
      // ```
      // and use it as additional repository
      assert(eval("foo.resolvedIvyDeps").isSuccess)
      val model = os.read(workspacePath / "out/mill-build/parseBuildFiles.json")
      assert(model.contains("""file:///tmp/testrepo""""))
    }
  }
}
