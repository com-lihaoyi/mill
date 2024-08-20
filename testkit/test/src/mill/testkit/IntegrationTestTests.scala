package mill.testkit

import utest._

object IntegrationTestTests extends TestSuite {

  def tests: Tests = Tests {

    test("integration") {
      val tester = new IntegrationTester(
        clientServerMode = true,
        workspaceSourcePath =
          os.pwd / "testkit" / "test" / "resources" / "integration-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      val res1 = tester.eval("testTask")
      assert(res1.isSuccess)
      assert(res1.err.contains("compiling 1 Scala source")) // compiling the `build.sc`
      assert(tester.meta("testTask").value[String] == "HELLO WORLD SOURCE FILE")

      tester.modifyFile(tester.workspacePath / "source-file.txt", _ + "!!!")

      val res2 = tester.eval("testTask")
      assert(!res2.err.contains("compiling 1 Scala source")) // no need to re-compile `build.sc`
      assert(tester.meta("testTask").value[String] == "HELLO WORLD SOURCE FILE!!!")
    }
  }
}
