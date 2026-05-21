package mill
package kotlinlib

import mill.testkit.IntegrationTester
import utest.*

object DeletedKotlinTestSourceReproTests extends TestSuite {

  private val resourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  private val resourcePath = resourceRoot / "deleted-kotlin-test-source-repro"
  private val repoRoot = resourceRoot / os.up / os.up / os.up / os.up
  private val millExecutable = sys.env.get("MILL_EXECUTABLE_PATH")
    .map(os.Path(_, os.pwd))
    .getOrElse(repoRoot / "mill")

  val tests: Tests = Tests {
    test("deleted test source invalidates test compile output immediately") {
      val tester = IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourcePath,
        millExecutable = millExecutable,
        allowSharedOutputDir = false
      )
      try {
        val deletedSource = tester.workspacePath / "app/test/src/repro/DeletedFailingTest.kt"
        val deletedClass =
          tester.workspacePath / "out/app/test/compile.dest/classes/repro/DeletedFailingTest.class"

        val initialCompile = tester.eval("app.test.compile")
        assert(
          initialCompile.isSuccess,
          os.exists(deletedClass)
        )

        os.remove(deletedSource)

        val recompiled = tester.eval("app.test.compile")
        assert(
          recompiled.isSuccess,
          !os.exists(deletedClass)
        )

        val discovered = tester.eval("app.test.discoveredTestClasses")
        val discoveredClasses =
          if (discovered.isSuccess) tester.out("app.test.discoveredTestClasses").value[Seq[String]]
          else Nil

        assert(
          discovered.isSuccess,
          discoveredClasses == Seq("repro.StillHereTest")
        )
      } finally tester.close()
    }
  }
}
