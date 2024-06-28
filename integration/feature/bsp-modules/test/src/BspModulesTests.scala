package mill.integration
package local
import mill.bsp.Constants
import utest._

object BspModulesTests extends IntegrationTestSuite {
  val bsp4jVersion: String = sys.props.getOrElse("BSP4J_VERSION", ???)

  def tests: Tests = Tests {
    test("BSP module with foreign modules") {
      test("can be installed") {
        val workspacePath = initWorkspace()
        assert(eval("mill.bsp.BSP/install"))
        os.exists(workspacePath / Constants.bspDir / s"${Constants.serverName}.json") ==> true
      }
      test("ModuleUtils resolves all referenced transitive modules") {
        val workspacePath = initWorkspace()
        assert(eval("validate"))
        val file = workspacePath / "out" / "validate.dest" / "transitive-modules.json"
        assert(os.exists(file))
        val readModules = os.read.lines(file).sorted
        val expectedModules = Seq(
          "", // the root module has no segments at all
          "HelloBsp",
          "HelloBsp.test",
          "proj1.proj1",
          "proj2.proj2"
          // "foreign-modules.proj3.proj3" // still not detected
        ).sorted
        assert(readModules == expectedModules)
      }
    }
  }
}
