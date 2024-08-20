package mill.testkit

import utest._

object IntegrationTestTests extends TestSuite {

  def tests: Tests = Tests {

    test("sources") {
      val tester = new IntegrationTester(
        clientServerMode = true,
        workspaceSourcePath = os.pwd / "testkit" / "test" / "resources" / "integration-test-example-project",
        millExecutable =
      )

      tester.eval("testTask")
      assert(tester.meta("testTask").value[String] == "HELLO WORLD SOURCE FILE")
      tester.modifyFile(tester.workspacePath / "source-file.txt", _ + "!!!")
      tester.eval("testTask")
      assert(tester.meta("testTask").value[String] == "HELLO WORLD SOURCE FILE!!!")
    }
  }
}
