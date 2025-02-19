package mill.testkit

import mill.constants.ServerFiles
import utest.*

trait IntegrationTesterTests extends TestSuite with IntegrationTestSuite {

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

        val suffix = if (clientServerMode) "mill-server" else "mill-no-server"
        assert(os.exists(tester.workspacePath / "out" / suffix))
         tester.workspacePath
      }

      // Make sure processId file is correctly removed to ensure the Mill
      // server process shuts down
      val remainingProcessIdFiles =
        os.walk(workspacePath / "out").filter(_.last == ServerFiles.processId)
      assert(remainingProcessIdFiles.isEmpty)

    }
  }
}
object IntegrationTesterTestsServer extends IntegrationTesterTests{
  def clientServerMode = true
}
object IntegrationTesterTestsFork extends IntegrationTesterTests{
  def clientServerMode = false
}
