package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object BloopTests extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    test("test") - {

      test("root module bloop config should be created") - integrationTest { tester =>
        import tester._
        val res = eval("mill.contrib.bloop.Bloop/install")
        assert(res.isSuccess)
        assert(os.exists(workspacePath / ".bloop/root-module.json"))
      }
      test("mill-build module bloop config should be created") - integrationTest { tester =>
        import tester._
        val installResult: Boolean = eval("mill.contrib.bloop.Bloop/install").isSuccess
        val millBuildJsonFile = workspacePath / ".bloop/mill-build-.json"
        assert(installResult)
        assert(os.exists(millBuildJsonFile))
      }

      test("mill-build config should contain build.mill source") - integrationTest { tester =>
        import tester._
        val millBuildJsonFile = workspacePath / ".bloop/mill-build-.json"
        val installResult: Boolean = eval("mill.contrib.bloop.Bloop/install").isSuccess
        val config = ujson.read(os.read.stream(millBuildJsonFile))
        assert(installResult)
        assert(config("project")("sources").arr.exists(path =>
          os.Path(path.str).last == "build.mill"
        ))
      }
    }
  }
}
