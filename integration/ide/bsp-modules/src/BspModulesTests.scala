package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import mill.bsp.Constants
import utest._

object BspModulesTests extends UtestIntegrationTestSuite {
  val bsp4jVersion: String = sys.props.getOrElse("BSP4J_VERSION", Constants.bspProtocolVersion)

  def tests: Tests = Tests {
    test("BSP module with foreign modules") {
      test("can be installed") - integrationTest { tester =>
        import tester._
        val res = eval("mill.bsp.BSP/install")
        assert(res.isSuccess)
        os.exists(workspacePath / Constants.bspDir / s"${Constants.serverName}.json") ==> true

        eval("shutdown")
        Thread.sleep(1000)
        val checkRes = eval("checkExecutable", cwd = workspacePath)
        assert(checkRes.exitCode == 0)
        assert(checkRes.out.contains("checkExecutable succeeded"))
        ()
      }
      test("ModuleUtils resolves all referenced transitive modules") - integrationTest { tester =>
        import tester._
        val res = eval("validate")
        assert(res.isSuccess)
        val file = workspacePath / "out/validate.dest/transitive-modules.json"
        assert(os.exists(file))
        val readModules = os.read.lines(file).sorted
        val expectedModules = Seq(
          "", // the root module has no segments at all
          "HelloBsp",
          "HelloBsp.test",
          "proj1",
          "proj2",
          "proj3",
          "selective"
        ).sorted
        assert(readModules == expectedModules)
      }
    }
  }
}
