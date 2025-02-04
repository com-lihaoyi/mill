package mill.eval

import mill.util.TestUtil.Test
import mill.define.{Discover, TargetImpl, Task}
import mill.{T, Module}
import mill.util.{TestGraphs, TestUtil}
import mill.testkit.{TestBaseModule, UnitTester}
import mill.api.Strict.Agg
import os.SubPath
import utest.*
import utest.framework.TestPath

class Checker[T <: mill.testkit.TestBaseModule](module: T, threadCount: Option[Int] = Some(1)) {
  // Make sure data is persisted even if we re-create the evaluator each time

  val evaluator = UnitTester(module, null, threads = threadCount).evaluator

  def apply(
      target: Task[_],
      expValue: Any,
      expEvaled: Agg[Task[_]],
      // How many "other" tasks were evaluated other than those listed above.
      // Pass in -1 to skip the check entirely
      extraEvaled: Int = 0,
      // Perform a second evaluation of the same tasks, and make sure the
      // outputs are the same but nothing was evaluated. Disable this if you
      // are directly evaluating tasks which need to re-evaluate every time
      secondRunNoOp: Boolean = true
  ) = {

    val evaled = evaluator.evaluate(Agg(target))

    val (matchingReturnedEvaled, extra) =
      evaled.evaluated.indexed.partition(expEvaled.contains)

    assert(
      evaled.values.map(_.value) == Seq(expValue),
      matchingReturnedEvaled.toSet == expEvaled.toSet,
      extraEvaled == -1 || extra.length == extraEvaled
    )

    // Second time the value is already cached, so no evaluation needed
    if (secondRunNoOp) {
      val evaled2 = evaluator.evaluate(Agg(target))
      val expectedSecondRunEvaluated = Agg()
      assert(
        evaled2.values.map(_.value) == evaled.values.map(_.value),
        evaled2.evaluated == expectedSecondRunEvaluated
      )
    }
  }
}
