package mill.define

import mill.api.{Result, Val, ExecResult}
private[mill] trait SelectiveExecution {
  import SelectiveExecution._
  def computeHashCodeSignatures(
      transitiveNamed: Seq[NamedTask[?]],
      codeSignatures: Map[String, Int]
  ): Map[String, Int]

  def computeDownstream(
      transitiveNamed: Seq[NamedTask[?]],
      oldHashes: Metadata,
      newHashes: Metadata
  ): (Set[Task[?]], Seq[Task[Any]])

  def saveMetadata(metadata: SelectiveExecution.Metadata): Unit

  def computeChangedTasks(
      tasks: Seq[String]
  ): Result[ChangedTasks]

  def computeChangedTasks0(tasks: Seq[NamedTask[?]]): ChangedTasks

  def resolve0(tasks: Seq[String]): Result[Array[String]]

  def resolveChanged(tasks: Seq[String]): Result[Seq[String]]

  def resolveTree(tasks: Seq[String]): Result[ujson.Value]

  def computeMetadata(
      tasks: Seq[NamedTask[?]]
  ): (SelectiveExecution.Metadata, Map[Task[?], ExecResult[Val]])
}
object SelectiveExecution {
  case class Metadata(inputHashes: Map[String, Int], codeSignatures: Map[String, Int])

  implicit val rw: upickle.default.ReadWriter[Metadata] = upickle.default.macroRW

  case class ChangedTasks(
      resolved: Seq[NamedTask[?]],
      changedRootTasks: Set[NamedTask[?]],
      downstreamTasks: Seq[NamedTask[?]],
      results: Map[Task[?], ExecResult[Val]]
  )
}
