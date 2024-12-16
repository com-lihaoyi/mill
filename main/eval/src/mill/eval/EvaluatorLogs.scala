package mill.eval

import mill.define.InputImpl
import mill.main.client.OutFiles
import mill.util.SpanningForest
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.EnumerationHasAsScala

private[mill] object EvaluatorLogs {
  def logDependencyTree(
      interGroupDeps: Map[Terminal, Seq[Terminal]],
      indexToTerminal: Array[Terminal],
      terminalToIndex: Map[Terminal, Int],
      outPath: os.Path
  ): Unit = {
    SpanningForest.writeJsonFile(
      outPath / OutFiles.millDependencyTree,
      indexToTerminal.map(t => interGroupDeps.getOrElse(t, Nil).map(terminalToIndex).toArray),
      indexToTerminal.indices.toSet,
      indexToTerminal(_).render
    )
  }
  def logInvalidationTree(
      interGroupDeps: Map[Terminal, Seq[Terminal]],
      indexToTerminal: Array[Terminal],
      terminalToIndex: Map[Terminal, Int],
      outPath: os.Path,
      uncached: ConcurrentHashMap[Terminal, Unit],
      changedValueHash: ConcurrentHashMap[Terminal, Unit]
  ): Unit = {

    val reverseInterGroupDeps = interGroupDeps
      .iterator
      .flatMap { case (k, vs) => vs.map(_ -> k) }
      .toSeq
      .groupMap(_._1)(_._2)

    val changedTerminalIndices = changedValueHash.keys().asScala.toSet
    val downstreamIndexEdges = indexToTerminal
      .map(t =>
        if (changedTerminalIndices(t))
          reverseInterGroupDeps.getOrElse(t, Nil).map(terminalToIndex).toArray
        else Array.empty[Int]
      )

    val edgeSourceIndices = downstreamIndexEdges
      .zipWithIndex
      .collect { case (es, i) if es.nonEmpty => i }
      .toSet

    SpanningForest.writeJsonFile(
      outPath / OutFiles.millInvalidationTree,
      downstreamIndexEdges,
      uncached.keys().asScala
        .flatMap { uncachedTask =>
          val uncachedIndex = terminalToIndex(uncachedTask)
          Option.when(
            // Filter out input and source tasks which do not cause downstream invalidations
            // from the invalidation tree, because most of them are un-interesting and the
            // user really only cares about (a) inputs that cause downstream tasks to invalidate
            // or (b) non-input tasks that were invalidated alone (e.g. due to a codesig change)
            !uncachedTask.task.isInstanceOf[InputImpl[_]] || edgeSourceIndices(uncachedIndex)
          ) {
            uncachedIndex
          }
        }
        .toSet,
      indexToTerminal(_).render
    )
  }
}
