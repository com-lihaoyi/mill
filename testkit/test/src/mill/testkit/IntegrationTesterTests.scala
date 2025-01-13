package mill.testkit

import mill.main.client.ServerFiles
import utest.*

object IntegrationTesterTests extends TestSuite with IntegrationTestSuite {
  def clientServerMode = true
  def workspaceSourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "integration-test-example-project"
  def millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
  def tests: Tests = Tests {

    test("integration") {

      val workspacePath = integrationTest { tester =>
        val res1 = tester.eval("testTask")
        assert(res1.isSuccess)
        assert(res1.err.contains("compiling 1 Scala source")) // compiling the `build.mill`
        assert(tester.out("testTask").value[String] == "HELLO WORLD SOURCE FILE")

        tester.modifyFile(tester.workspacePath / "source-file.txt", _ + "!!!")

        val res2 = tester.eval("testTask")
        assert(!res2.err.contains("compiling 1 Scala source")) // no need to re-compile `build.mill`
        assert(tester.out("testTask").value[String] == "HELLO WORLD SOURCE FILE!!!")

        tester.workspacePath
      }

      // Make sure serverId file is correctly removed to ensure the Mill
      // server process shuts down
      val remainingServerIdFiles =
        os.walk(workspacePath / "out").filter(_.last == ServerFiles.serverId)
      assert(remainingServerIdFiles.isEmpty)

    }
  }
}
