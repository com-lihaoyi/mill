package myplugin
import mill.testkit.IntegrationTester
import utest.*

object IntegrationTests extends TestSuite {

  println("initializing myplugin.IntegrationTest")
  def tests: Tests = Tests {
    println("initializing myplugin.IntegrationTest.tests")

    test("integration") {
      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      val tester = new IntegrationTester(
        daemonMode = true,
        workspaceSourcePath = resourceFolder / "integration-test-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      val res1 = tester.eval("run")
      assert(res1.isSuccess)
      assert(res1.err.contains("compiling 1 Java source")) // compiling the `build.mill`
      assert(res1.out.contains("Line Count: 18"))
      assert(tester.out("lineCount").value[Int] == 18)

      val res2 = tester.eval("run") // No need to recompile when nothing changed
      assert(!res2.err.contains("compiling 1 Java source"))
      assert(res2.out.contains("Line Count: 18"))
      assert(tester.out("lineCount").value[Int] == 18)

      tester.modifyFile(tester.workspacePath / "src/foo/Foo.java", _ + "\n")

      val res3 = tester.eval("run") // Additional newline forces recompile and increases line count
      assert(res3.err.contains("compiling 1 Java source"))
      assert(res3.out.contains("Line Count: 19"))
      assert(tester.out("lineCount").value[Int] == 19)
    }
  }
}
