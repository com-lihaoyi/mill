package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object ImportRepoTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()
    test("test") - {
      // Make sure, we propery parse a line:
      // ```
      //   import $repo.`file:///tmp/testrepo`
      // ```
      // and use it as additional repository
      assert(eval("foo.resolvedIvyDeps").isSuccess)
      val model = os.read(workspacePath / "out" / "mill-build" / "parseBuildFiles.json")
      assert(model.contains("""file:///tmp/testrepo""""))
    }
  }
}
