package mill.testkit

import utest._

object IntegrationTests extends TestSuite {

  def tests: Tests = Tests {

    test("integration") {
      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
      val tester = new IntegrationTester(
        clientServerMode = true,
        workspaceSourcePath = resourceFolder / "integration-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      val res1 = tester.eval("run")
      assert(res1.isSuccess)
      assert(res1.err.contains("compiling 1 Scala source")) // compiling the `build.sc`
      assert(res1.out.contains("Line Count: 17"))
      assert(tester.outJson("lineCount").value[Int] == 17)

      tester.modifyFile(tester.workspacePath / "source-file.txt", _ + "!!!")

      val res2 = tester.eval("run")
      assert(!res2.err.contains("compiling 1 Scala source")) // no need to re-compile `build.sc`
      assert(res1.out.contains("Line Count: 17"))
    }
  }
}
