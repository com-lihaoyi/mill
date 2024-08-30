package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object ImportFileUnsupported extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("Import $file syntax in"))
      assert(
        res.err.contains(
          "is no longer supported. Any `foo/bar.sc` file in a folder next to a " +
          "`foo/package.sc` can be directly imported via `import foo.bar`"
        )
      )
    }
  }
}
