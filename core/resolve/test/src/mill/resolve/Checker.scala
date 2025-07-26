package mill.resolve

import mill.api.Result
import mill.api.internal.RootModule0
import mill.api.{SelectMode, Task}
import mill.exec.ExecutionContexts
import utest.*

class Checker[T <: RootModule0](module: T) {

  def apply(
      selectorString: String,
      expected0: Result[Set[T => Task.Named[?]]],
      expectedMetadata: Set[String] = Set()
  ) = checkSeq(Seq(selectorString), expected0, expectedMetadata)

  def checkSeq(
      selectorStrings: Seq[String],
      expected0: Result[Set[T => Task.Named[?]]],
      expectedMetadata: Set[String] = Set()
  ) = {
    val expected = expected0.map(_.map(_(module)))

    val resolvedTasks = resolveTasks(selectorStrings)
    val resolvedStrings = resolvedTasks.map(_.map(_.toString).toSet[String])
    val expectedStrings = expected.map(_.map(_.toString))
    assert(resolvedStrings == expectedStrings)

    val resolvedMetadata = resolveMetadata(selectorStrings)
    assert(
      expectedMetadata.isEmpty ||
        resolvedMetadata.map(_.toSet) == Result.Success(expectedMetadata)
    )
    selectorStrings.mkString(" ")
  }

  def checkSeq0(
      selectorStrings: Seq[String],
      check: Result[List[Task.Named[?]]] => Boolean,
      checkMetadata: Result[List[String]] => Boolean = _ => true
  ) = {

    val resolvedTasks = resolveTasks(selectorStrings)
    assert(check(resolvedTasks))

    val resolvedMetadata = resolveMetadata(selectorStrings)
    assert(checkMetadata(resolvedMetadata))
  }

  def resolveTasks(selectorStrings: Seq[String]) = {
    Resolve.Tasks.resolve0(
      module,
      selectorStrings,
      SelectMode.Separated,
      false,
      false,
      ec = ExecutionContexts.RunNow
    )
  }

  def resolveMetadata(selectorStrings: Seq[String]) = {
    Resolve.Segments.resolve0(
      module,
      selectorStrings,
      SelectMode.Separated,
      false,
      false,
      ec = ExecutionContexts.RunNow
    ).map(_.map(_.render))
  }
}
