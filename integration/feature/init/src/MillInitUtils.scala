package mill.integration

import mill.testkit.IntegrationTester
import mill.testkit.IntegrationTester.EvalResult
import utest.*

import scala.collection.immutable.SortedSet

object MillInitUtils {
  val defaultInitCommandWithoutMerge =
    Seq("init", "--base-module", "BaseModule", "--deps-object", "Deps")
  val defaultInitCommand =
    defaultInitCommandWithoutMerge :+ "--merge"

  // `SortedSet` is used so it's easier when the results are compared or printed.
  case class SplitTaskResults(
      all: SortedSet[String],
      successful: SortedSet[String],
      failed: SortedSet[String]
  ) {
    {
      val successfulAndFailed = successful ++ failed
      require(all == successfulAndFailed, s"$all != $successfulAndFailed")
    }

    // Quotes added so the actual results can be easily copied into code.
    override def toString: String =
      s"SplitTaskResults(successful=${successful.map(task => s"\"$task\"")}, " +
        s"failed=${failed.map(task => s"\"$task\"")})"
  }
  object SplitTaskResults {
    def apply(
        all: SortedSet[String] | Null = null,
        successful: SortedSet[String] | Null = null,
        failed: SortedSet[String] | Null = null
    ) =
      new SplitTaskResults(
        if (all != null) all else successful ++ failed,
        if (successful != null) successful else all diff failed,
        if (failed != null) failed else all diff successful
      )
  }

  enum ModuleTaskTestMode {

    /**
     * Fail the whole test immediately if a `compile` or `test` task fails.
     * @param combineSuccessful combine the expected successful tasks into one command to run to speed up the process, especially on CI
     */
    case FailFast(combineSuccessful: Boolean)

    /**
     * Print the actual [[SplitTaskResults]] for more efficient debugging when the test fails.
     */
    case ShowActual
  }

  /**
   * @param expectedAllSourceFileNums a map from the `allSourceFiles` task to the number of files
   * @param expectedCompileTaskResults [[None]] to denote that the `resolve __.compile` task fails
   * @param expectedTestTaskResults [[None]] to denote that the `resolve __.test` task fails
   */
  def testMillInit(
      tester: IntegrationTester,
      initCommand: Seq[String] = defaultInitCommand,
      modifyConvertedBuild: () => Unit = () => (),
      expectedInitResult: Boolean = true,
      expectedAllSourceFileNums: Map[String, Int],
      moduleTaskTestMode: ModuleTaskTestMode = ModuleTaskTestMode.FailFast(true),
      // expectedCompileResult: Boolean,
      expectedCompileTaskResults: Option[SplitTaskResults],
      expectedTestTaskResults: Option[SplitTaskResults]
  ) = {
    import tester.*

    val initResult = eval(initCommand)
    assert(initResult.isSuccess == expectedInitResult)

    modifyConvertedBuild()

    {
      val resolveResult = eval(("resolve", s"__.allSourceFiles"))
      assert(resolveResult.isSuccess)
      val numSourceFilesMap = outSeq(resolveResult).map(task => {
        val result = eval(("show", task))
        assert(result.isSuccess)
        val numLines = result.out.linesIterator.size
        task -> (if (numLines == 1) 0 else numLines - 2)
      }).toMap
      assert(expectedAllSourceFileNums == numSourceFilesMap)
    }

    /*
    val compileResult = eval("compile")
    assert(compileResult.isSuccess == expectedCompileResult)
     */

    moduleTaskTestMode match {
      case ModuleTaskTestMode.FailFast(combineSuccessful) =>
        def testSplitTaskResults(
            taskName: String,
            expectedTaskResults: Option[SplitTaskResults]
        ) = {
          val resolveAllTasksResult = eval(("resolve", s"__.$taskName"))
          expectedTaskResults.fold(
            assert(!resolveAllTasksResult.isSuccess)
          )(expected => {
            assert(resolveAllTasksResult.isSuccess)
            val resolvedAllTasks = outSortedSet(resolveAllTasksResult)
            assertEqWithFailureComparisonOnSeparateLines(expected.all, resolvedAllTasks)

            if (combineSuccessful) {
              val tasks = expected.successful
              if (tasks.nonEmpty)
                assert(eval(
                  if (tasks.size == 1) tasks.head
                  else tasks.mkString("{", ",", "}")
                ).isSuccess)
            } else
              for (task <- expected.successful)
                Predef.assert(eval(task).isSuccess, s"task $task failed")

            for (task <- expected.failed)
              Predef.assert(!eval(task).isSuccess, s"task $task succeeded")
          })
        }

        testSplitTaskResults("compile", expectedCompileTaskResults)
        testSplitTaskResults("test", expectedTestTaskResults)
      case ModuleTaskTestMode.ShowActual =>
        def getSplitTaskResults(taskName: String) = {
          val resolveAllTasksResult = eval(("resolve", s"__.$taskName"))
          Option.when(resolveAllTasksResult.isSuccess) {
            val resolvedAllTasks = outSortedSet(resolveAllTasksResult)
            val (successful, failed) = resolvedAllTasks.partition(task => eval(task).isSuccess)
            SplitTaskResults(resolvedAllTasks, successful, failed)
          }
        }

        assertEqWithFailureComparisonOnSeparateLines(
          expectedCompileTaskResults,
          getSplitTaskResults("compile")
        )
        assertEqWithFailureComparisonOnSeparateLines(
          expectedTestTaskResults,
          getSplitTaskResults("test")
        )
    }
  }

  private def assertEqWithFailureComparisonOnSeparateLines[T](expected: T, actual: T) =
    Predef.assert(
      expected == actual,
      s"""
         |expected: $expected
         |actual: $actual
         |""".stripMargin
    )

  def outSeq(evalResult: EvalResult) =
    evalResult.out.linesIterator.toSeq.sorted

  def outSortedSet(evalResult: EvalResult) =
    SortedSet.from(evalResult.out.linesIterator)

  def compileTask(module: String): String =
    s"$module.compile"
  private def testModuleOrTask(module: String): String =
    s"$module.test"
  def testTask(module: String): String =
    testModuleOrTask(module)
  def testModule(module: String): String =
    testModuleOrTask(module)
  def testCompileTask(module: String): String =
    compileTask(testModule(module))
  def allCompileTasks(module: String): Seq[String] =
    Seq(compileTask(module), testCompileTask(module))

  def writeMillJvmVersion(workspace: os.Path, jvmId: String) =
    os.write(workspace / ".mill-jvm-version", jvmId)
  def writeMillJvmVersionTemurin11(workspace: os.Path) =
    writeMillJvmVersion(workspace, "temurin:11")
}
