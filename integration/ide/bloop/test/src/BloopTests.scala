package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object BloopTests extends IntegrationTestSuite {
  initWorkspace()

  val installResult: Boolean = eval("mill.contrib.bloop.Bloop/install")

  val tests: Tests = Tests {
    test("test") - {
      assert(installResult)

      "root module bloop config should be created" - {
        assert(os.exists(wd / ".bloop" / "root-module.json"))
      }
      val millBuildJsonFile = wd / ".bloop" / "mill-build-.json"
      "mill-build module bloop config should be created" - {
        assert(os.exists(millBuildJsonFile))
      }

      val config = ujson.read(os.read.stream(millBuildJsonFile))
      "mill-build config should contain build.sc source" - {
        assert(config("project")("sources").arr.exists(path =>
          os.Path(path.str).last == "build.sc"
        ))
      }
    }
  }
}
