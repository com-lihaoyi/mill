package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object BloopTests extends IntegrationTestSuite {
  initWorkspace()

  val installResult: Boolean = eval("mill.contrib.bloop.Bloop/install").isSuccess

  val tests: Tests = Tests {
    test("test") - {
      assert(installResult)

      test("root module bloop config should be created") {
        assert(os.exists(workspacePath / ".bloop" / "root-module.json"))
      }
      val millBuildJsonFile = workspacePath / ".bloop" / "mill-build-.json"
      test("mill-build module bloop config should be created") {
        assert(os.exists(millBuildJsonFile))
      }

      val config = ujson.read(os.read.stream(millBuildJsonFile))
      test("mill-build config should contain build.mill source") {
        assert(config("project")("sources").arr.exists(path =>
          os.Path(path.str).last == "build.mill"
        ))
      }
    }
  }
}
