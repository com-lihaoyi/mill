package mill.resolve

import mill.api.Result
import mill.define.{NamedTask, SelectMode}
import utest.*

class Checker[T <: mill.define.BaseModule](module: T) {

  def apply(
      selectorString: String,
      expected0: Result[Set[T => NamedTask[?]]],
      expectedMetadata: Set[String] = Set()
  ) = checkSeq(Seq(selectorString), expected0, expectedMetadata)

  def checkSeq(
      selectorStrings: Seq[String],
      expected0: Result[Set[T => NamedTask[?]]],
      expectedMetadata: Set[String] = Set()
  ) = {
    val expected = expected0.map(_.map(_(module)))

    val resolvedTasks = resolveTasks(selectorStrings)
    assert(
      resolvedTasks.map(_.map(_.toString).toSet[String]) ==
        expected.map(_.map(_.toString))
    )

    val resolvedMetadata = resolveMetadata(selectorStrings)
    assert(
      expectedMetadata.isEmpty ||
        resolvedMetadata.map(_.toSet) == Result.Success(expectedMetadata)
    )
    selectorStrings.mkString(" ")
  }

  def checkSeq0(
      selectorStrings: Seq[String],
      check: Result[List[NamedTask[?]]] => Boolean,
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
      false
    )
  }

  def resolveMetadata(selectorStrings: Seq[String]) = {
    Resolve.Segments.resolve0(
      module,
      selectorStrings,
      SelectMode.Separated,
      false,
      false
    ).map(_.map(_.render))
  }
}
