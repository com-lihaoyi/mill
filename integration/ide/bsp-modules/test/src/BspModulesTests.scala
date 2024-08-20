package mill.integration

import mill.testkit.IntegrationTestSuite
import mill.bsp.Constants
import utest._

object BspModulesTests extends IntegrationTestSuite {
  val bsp4jVersion: String = sys.props.getOrElse("BSP4J_VERSION", ???)

  def tests: Tests = Tests {
    test("BSP module with foreign modules") {
      test("can be installed") {
        initWorkspace()
        assert(eval("mill.bsp.BSP/install").isSuccess)
        os.exists(workspacePath / Constants.bspDir / s"${Constants.serverName}.json") ==> true
      }
      test("ModuleUtils resolves all referenced transitive modules") {
        initWorkspace()
        assert(eval("validate").isSuccess)
        val file = workspacePath / "out" / "validate.dest" / "transitive-modules.json"
        assert(os.exists(file))
        val readModules = os.read.lines(file).sorted
        val expectedModules = Seq(
          "", // the root module has no segments at all
          "HelloBsp",
          "HelloBsp.test",
          "proj1.module",
          "proj2.module"
          // "foreign-modules.proj3.proj3" // still not detected
        ).sorted
        assert(readModules == expectedModules)
      }
    }
  }
}
