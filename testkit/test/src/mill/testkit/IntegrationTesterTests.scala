package mill.testkit

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
        assert(res1.err.contains("compiling 3 Scala sources")) // compiling the `build.mill`
        assert(tester.out("testTask").value[String] == "HELLO WORLD SOURCE FILE")

        tester.modifyFile(tester.workspacePath / "source-file.txt", _ + "!!!")

        val res2 = tester.eval("testTask")
        assert(!res2.err.contains("compiling 1 Scala source")) // no need to re-compile `build.mill`
        assert(
          !res2.err.contains("compiling 3 Scala sources")
        ) // no need to re-compile `build.mill`
        assert(tester.out("testTask").value[String] == "HELLO WORLD SOURCE FILE!!!")

        val suffix = if (daemonMode) "mill-daemon" else "mill-no-daemon"
        assert(os.exists(tester.workspacePath / "out" / suffix))

        // Make sure processId file(s) is present while the test is running
        val processIdFiles = TestkitTestUtils.getProcessIdFiles(tester.workspacePath)
        assert(processIdFiles.nonEmpty)
        tester.workspacePath
      }

      // Make sure processId file is correctly removed to ensure the Mill
      // server process shuts down
      val processIdFiles = TestkitTestUtils.getProcessIdFiles(workspacePath)
      assert(processIdFiles.isEmpty)

    }
  }
}
object IntegrationTesterTestsServer extends IntegrationTesterTests {
  def daemonMode = true
}
object IntegrationTesterTestsFork extends IntegrationTesterTests {
  def daemonMode = false
}
