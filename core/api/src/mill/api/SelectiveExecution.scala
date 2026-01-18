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
  )
  object Metadata {
    case class Computed(
        metadata: Metadata,
        results: Map[Task[?], ExecResult[Val]]
    )
  }

  implicit val rw: upickle.ReadWriter[Metadata] = upickle.macroRW

  enum InvalidationReason {
    case MillVersionChanged(oldVersion: String, newVersion: String)
    case MillJvmVersionChanged(oldVersion: String, newVersion: String)
    case InputChanged
    case CodeChanged
    case BuildOverrideChanged
    case ForcedRun
  }

  case class ChangedTasks(
      resolved: Seq[Task.Named[?]],
      changedRootTasks: Set[Task.Named[?]],
      downstreamTasks: Seq[Task.Named[?]],
      /** Maps task name to the reason it was invalidated (only for root tasks) */
      invalidationReasons: Map[String, InvalidationReason] = Map.empty,
      /** Set if all tasks were invalidated due to mill-version change */
      millVersionChanged: Option[(String, String)] = None,
      /** Set if all tasks were invalidated due to mill-jvm-version change */
      millJvmVersionChanged: Option[(String, String)] = None
  )
  object ChangedTasks {

    /** Indicates that all of the passed in tasks were changed. */
    def all(tasks: Seq[Task.Named[?]], reason: InvalidationReason): ChangedTasks = {
      val (millVersionChanged, millJvmVersionChanged) = reason match {
        case InvalidationReason.MillVersionChanged(old, newV) => (Some((old, newV)), None)
        case InvalidationReason.MillJvmVersionChanged(old, newV) => (None, Some((old, newV)))
        case _ => (None, None)
      }
      ChangedTasks(
        tasks,
        tasks.toSet,
        tasks,
        tasks.map(t => t.ctx.segments.render -> reason).toMap,
        millVersionChanged = millVersionChanged,
        millJvmVersionChanged = millJvmVersionChanged
      )
    }
  }
}
