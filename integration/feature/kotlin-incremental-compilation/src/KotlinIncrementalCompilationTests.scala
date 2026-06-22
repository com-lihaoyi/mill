package mill.integration

import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}

import utest.*

// Regression test: adding a variant to an upstream sealed type must invalidate
// downstream `when` exhaustiveness checks via the Kotlin Build Tools API IC engine.
object KotlinIncrementalCompilationTests extends UtestIntegrationTestSuite {

  private val nonExhaustiveWhenError = "'when' expression must be exhaustive."
  private val missingCaseName = "Oof"

  val tests: Tests = Tests {
    test("upstream sealed additions invalidate downstream exhaustive when") - integrationTest {
      tester =>
        import tester.*

        val useBtApiEval = eval("consumer.kotlincUseBtApi")
        assert(useBtApiEval.isSuccess)
        assert(out("consumer.kotlincUseBtApi").value[Boolean])

        assert(eval("consumer.compile").isSuccess)
        writeFeatureBranchSources(workspacePath)

        val branchSwitchCompile = eval("consumer.compile")
        val cleanAfterSwitch = eval(("clean", "consumer"))
        val cleanCompile = eval("consumer.compile")

        assert(cleanAfterSwitch.isSuccess)
        assert(failsWithMissingOof(cleanCompile))
        assert(failsWithMissingOof(branchSwitchCompile))
    }
  }

  private def failsWithMissingOof(result: IntegrationTester.EvalResult): Boolean =
    !result.isSuccess &&
      result.err.contains(nonExhaustiveWhenError) &&
      result.err.contains(missingCaseName)

  private def writeFeatureBranchSources(workspacePath: os.Path): Unit = {
    os.write.over(
      workspacePath / "model/src/Foo.kt",
      """sealed interface Foo {
        |    data object Bar : Foo
        |
        |    data object Oof : Foo
        |}
        |""".stripMargin
    )
  }
}
