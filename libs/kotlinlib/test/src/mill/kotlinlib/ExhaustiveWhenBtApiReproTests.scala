package mill
package kotlinlib

import mill.testkit.IntegrationTester
import utest.*

object ExhaustiveWhenBtApiReproTests extends TestSuite {

  private val resourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  private val resourcePath = resourceRoot / "exhaustive-when-btapi-repro"
  private val repoRoot = resourceRoot / os.up / os.up / os.up / os.up
  private val millExecutable = sys.env.get("MILL_EXECUTABLE_PATH")
    .map(os.Path(_, os.pwd))
    .getOrElse(repoRoot / "mill")

  private val nonExhaustiveWhenError = "'when' expression must be exhaustive."
  private val missingCaseName = "Oof"

  val tests: Tests = Tests {
    test("upstream sealed additions invalidate downstream exhaustive when") {
      val tester = IntegrationTester(
        daemonMode = false,
        workspaceSourcePath = resourcePath,
        millExecutable = millExecutable,
        allowSharedOutputDir = false
      )

      try {
        val useBtApiEval = tester.eval(("consumer.kotlincUseBtApi"))
        assert(useBtApiEval.isSuccess)
        assert(tester.out("consumer.kotlincUseBtApi").value[Boolean])

        assert(tester.eval(("consumer.compile")).isSuccess)
        writeFeatureBranchSources(tester.workspacePath)

        val branchSwitchCompile = tester.eval(("consumer.compile"))
        val cleanAfterSwitch = tester.eval(("clean", "consumer"))
        val cleanCompile = tester.eval(("consumer.compile"))

        assert(cleanAfterSwitch.isSuccess)
        assert(failsWithMissingOof(cleanCompile))
        assert(failsWithMissingOof(branchSwitchCompile))
      } finally tester.close()
    }
  }

  private def failsWithMissingOof(result: IntegrationTester.EvalResult): Boolean =
    !result.isSuccess &&
      result.err.contains(nonExhaustiveWhenError) &&
      result.err.contains(missingCaseName)

  private def writeFeatureBranchSources(workspacePath: os.Path): Unit = {
    os.write.over(
      workspacePath / "model/src/repro/Foo.kt",
      """package repro
        |
        |sealed interface Foo {
        |    data object Bar : Foo
        |
        |    data object Oof : Foo
        |}
        |""".stripMargin
    )
  }
}
