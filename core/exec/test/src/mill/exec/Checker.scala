package mill.exec

import mill.api.Task
import mill.testkit.{TestRootModule, UnitTester}

import utest.*

class Checker[T <: mill.testkit.TestRootModule](
    module: T,
    threadCount: Option[Int] = Some(1),
    sourceRoot: os.Path = null
) {
  // Make sure data is persisted even if we re-create the evaluator each time

  val execution = UnitTester(module, sourceRoot, threads = threadCount).execution

  def apply(
      task: Task[?],
      expValue: Any,
      expEvaled: Seq[Task[?]],
      // How many "other" tasks were evaluated other than those listed above.
      // Pass in -1 to skip the check entirely
      extraEvaled: Int = 0,
      // Perform a second evaluation of the same tasks, and make sure the
      // outputs are the same but nothing was evaluated. Disable this if you
      // are directly evaluating tasks which need to re-evaluate every time
      secondRunNoOp: Boolean = true
  ) = {

    val evaled = execution.executeTasks(Seq(task))

    val (matchingReturnedEvaled, extra) =
      evaled.uncached.partition(expEvaled.contains)

    val evaledValues = evaled.values.map(_.value)
    assert(
      evaledValues == Seq(expValue),
      matchingReturnedEvaled.toSet == expEvaled.toSet,
      extraEvaled == -1 || extra.length == extraEvaled
    )

    // Second time the value is already cached, so no evaluation needed
    if (secondRunNoOp) {
      val evaled2 = execution.executeTasks(Seq(task))
      val expectedSecondRunEvaluated = Seq()
      assert(
        evaled2.values.map(_.value) == evaled.values.map(_.value),
        evaled2.uncached == expectedSecondRunEvaluated
      )
    }
  }
}
