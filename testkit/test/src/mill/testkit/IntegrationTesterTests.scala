package mill.testkit

import utest._

object IntegrationTesterTests extends TestSuite {

  def tests: Tests = Tests {

    test("integration") {
      val tester = new IntegrationTester(
        clientServerMode = true,
        workspaceSourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "integration-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      val res1 = tester.eval("testTask")
      assert(res1.isSuccess)
      assert(res1.err.contains("compiling 1 Scala source")) // compiling the `build.mill`
      assert(tester.out("testTask").value[String] == "HELLO WORLD SOURCE FILE")

      tester.modifyFile(tester.workspacePath / "source-file.txt", _ + "!!!")

      val res2 = tester.eval("testTask")
      assert(!res2.err.contains("compiling 1 Scala source")) // no need to re-compile `build.mill`
      assert(tester.out("testTask").value[String] == "HELLO WORLD SOURCE FILE!!!")
    }
  }
}
