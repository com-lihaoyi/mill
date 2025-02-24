package mill.integration

import mill.testkit.IntegrationTester
import utest.*

val defaultInitCommand =
  Seq("init", "--base-module", "BaseModule", "--deps-object", "Deps", "--merge")

case class SplitResolvedTasks(successful: Seq[String], failed: Seq[String]) {
  val all = (successful ++ failed).sorted
}

extension (tester: IntegrationTester)
  /**
   * @param expectedCompileTasks [[ None ]] to denote that the `resolve __.compile` task fails
   * @param expectedTestTasks [[ None ]] to denote that the `resolve __.test` task fails
   * @return
   */
  def testMillInit(
      initCommand: Seq[String] = defaultInitCommand,
      expectedInitResult: Boolean = true,
      // expectedCompileResult: Boolean,
      expectedCompileTasks: Option[SplitResolvedTasks],
      expectedTestTasks: Option[SplitResolvedTasks]
  ) = {
    import tester.*

    val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
    assert(initResult.isSuccess == expectedInitResult)

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
          expected.all == resolvedAllTasks,
          s"""
             |expected: ${expected.all}
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

extension (module: String) {
  def compileTask: String =
    s"$module.compile"
  private def testModuleOrTask: String =
    s"$module.test"
  def testTask: String =
    testModuleOrTask
  def testModule: String =
    testModuleOrTask
  def testCompileTask: String =
    testModule.compileTask
  def testTestTask: String =
    testModule.testTask
  def allCompileTasks: Seq[String] =
    Seq(compileTask, testCompileTask)
}
