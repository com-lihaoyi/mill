package mill.api

import mill.api.{ExecResult, Result, Val}
private[mill] trait SelectiveExecution {
  import SelectiveExecution.*
  def computeHashCodeSignatures(
      transitiveNamed: Seq[Task.Named[?]],
      codeSignatures: Map[String, Int]
  ): Map[String, Int]

  def computeDownstream(
      transitiveNamed: Seq[Task.Named[?]],
      oldHashes: Metadata,
      newHashes: Metadata
  ): (Set[Task[?]], Seq[Task[Any]])

  def saveMetadata(metadata: SelectiveExecution.Metadata): Unit

  def computeChangedTasks(
      tasks: Seq[String]
  ): Result[ChangedTasks]

  def computeChangedTasks0(
      tasks: Seq[Task.Named[?]],
      computedMetadata: SelectiveExecution.Metadata.Computed
  ): Option[ChangedTasks]

  def resolve0(tasks: Seq[String]): Result[Array[String]]

  def resolveChanged(tasks: Seq[String]): Result[Seq[String]]

  def resolveTree(tasks: Seq[String]): Result[ujson.Value]

  def computeMetadata(
      tasks: Seq[Task.Named[?]]
  ): SelectiveExecution.Metadata.Computed
}
object SelectiveExecution {
  case class Metadata(inputHashes: Map[String, Int], codeSignatures: Map[String, Int])
  object Metadata {
    case class Computed(
        metadata: Metadata,
        results: Map[Task[?], ExecResult[Val]]
    )
  }

  implicit val rw: upickle.ReadWriter[Metadata] = upickle.macroRW

  case class ChangedTasks(
      resolved: Seq[Task.Named[?]],
      changedRootTasks: Set[Task.Named[?]],
      downstreamTasks: Seq[Task.Named[?]]
  )
  object ChangedTasks {

    /** Indicates that all of the passed in tasks were changed. */
    def all(tasks: Seq[Task.Named[?]]): ChangedTasks = ChangedTasks(tasks, tasks.toSet, tasks)
  }
}
