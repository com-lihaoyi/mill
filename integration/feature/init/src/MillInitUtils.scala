package mill.integration

import mill.testkit.IntegrationTester
import utest.*

object MillInitUtils {
  val defaultInitCommand =
    Seq("init", "--base-module", "BaseModule", "--deps-object", "Deps", "--merge")

  case class SplitResolvedTasks(
      all: Seq[String],
      successful: Seq[String],
      failed: Seq[String]
  ) {
    val allSorted = all.sorted
    {
      val successfulAndFailedSorted = (successful ++ failed).sorted
      require(allSorted == successfulAndFailedSorted, s"$allSorted != $successfulAndFailedSorted")
    }
  }
  object SplitResolvedTasks {
    def apply(
        all: Seq[String] | Null = null,
        successful: Seq[String] | Null = null,
        failed: Seq[String] | Null = null
    ) =
      new SplitResolvedTasks(
        if (all != null) all else successful ++ failed,
        if (successful != null) successful else all diff failed,
        if (failed != null) failed else all diff successful
      )
  }

  /**
   * @param expectedCompileTasks [[ None ]] to denote that the `resolve __.compile` task fails
   * @param expectedTestTasks [[ None ]] to denote that the `resolve __.test` task fails
   * @return
   */
  def testMillInit(
      tester: IntegrationTester,
      initCommand: Seq[String] = defaultInitCommand,
      modifyConvertedBuild: () => Unit = () => (),
      expectedInitResult: Boolean = true,
      // expectedCompileResult: Boolean,
      expectedCompileTasks: Option[SplitResolvedTasks],
      expectedTestTasks: Option[SplitResolvedTasks]
  ) = {
    import tester.*

    val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
    assert(initResult.isSuccess == expectedInitResult)

    modifyConvertedBuild()

    /*
    val compileResult = eval("compile")
    assert(compileResult.isSuccess == expectedCompileResult)
     */

    def testAllResolvedTasks(taskName: String, expected: Option[SplitResolvedTasks]) = {
      val resolveAllTasksResult = eval(("resolve", s"__.$taskName"))
      expected.fold(
        assert(!resolveAllTasksResult.isSuccess)
      )(expected => {
        assert(resolveAllTasksResult.isSuccess)
        val resolvedAllTasks = resolveAllTasksResult.out.linesIterator.toSeq.sorted
        Predef.assert(
          expected.allSorted == resolvedAllTasks,
          s"""
             |expected: ${expected.allSorted}
             |resolved: $resolvedAllTasks
             |""".stripMargin
        )

        for (task <- expected.successful)
          Predef.assert(eval(task).isSuccess, s"task $task failed")

        for (task <- expected.failed)
          Predef.assert(!eval(task).isSuccess, s"task $task succeeded")
      })
    }

    testAllResolvedTasks("compile", expectedCompileTasks)
    testAllResolvedTasks("test", expectedTestTasks)
  }

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
