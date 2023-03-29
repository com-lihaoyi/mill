package mill.integration
package local
import mill.bsp.Constants
import utest._

object BspModulesTests extends IntegrationTestSuite.Cross {
  val bsp4jVersion = sys.props.getOrElse("BSP4J_VERSION", ???)

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
          "", // the root module has no segemnts at all
          "HelloBsp",
          "HelloBsp.test",
          "foreign-modules.proj1.build.proj1",
          "foreign-modules.proj2.build.proj2"
          // "foreign-modules.proj3.proj3" // still not detected
        ).sorted
        assert(readModules == expectedModules)
      }
    }
  }
}
