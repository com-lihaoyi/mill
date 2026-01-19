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

  def resolveTasks0(tasks: Seq[String]): Result[Array[Task.Named[?]]]

  def resolveChanged(tasks: Seq[String]): Result[Seq[String]]

  def resolveTree(tasks: Seq[String]): Result[ujson.Value]

  def computeMetadata(
      tasks: Seq[Task.Named[?]]
  ): SelectiveExecution.Metadata.Computed
}
object SelectiveExecution {

  case class Metadata(
      inputHashes: Map[String, Int],
      codeSignatures: Map[String, Int],
      @com.lihaoyi.unroll buildOverrideSignatures: Map[String, Int] = Map(),
      @com.lihaoyi.unroll forceRunTasks: Set[String] = Set(),
      @com.lihaoyi.unroll millVersion: String = "",
      @com.lihaoyi.unroll millJvmVersion: String = ""
  ) derives upickle.ReadWriter
  object Metadata {
    case class Computed(
        metadata: Metadata,
        results: Map[Task[?], ExecResult[Val]]
    )
  }

  case class ChangedTasks(
      resolved: Seq[Task.Named[?]],
      changedRootTasks: Set[Task.Named[?]],
      downstreamTasks: Seq[Task.Named[?]],
      /** Set if all tasks were invalidated due to mill-version change */
      millVersionChanged: Option[(String, String)] = None,
      /** Set if all tasks were invalidated due to mill-jvm-version change */
      millJvmVersionChanged: Option[(String, String)] = None
  )
  object ChangedTasks {

    /** Indicates that all of the passed in tasks were changed. */
    def all(
        tasks: Seq[Task.Named[?]],
        millVersionChanged: Option[(String, String)] = None,
        millJvmVersionChanged: Option[(String, String)] = None
    ): ChangedTasks = {
      ChangedTasks(
        tasks,
        tasks.toSet,
        tasks,
        millVersionChanged = millVersionChanged,
        millJvmVersionChanged = millJvmVersionChanged
      )
    }
  }
}
