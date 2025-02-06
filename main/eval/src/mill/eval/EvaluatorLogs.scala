package mill.eval

import mill.define.{InputImpl, Task}
import mill.main.client.OutFiles
import mill.util.SpanningForest

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.EnumerationHasAsScala

private[mill] object EvaluatorLogs {
  def logDependencyTree(
      interGroupDeps: Map[Task[_], Seq[Task[_]]],
      indexToTerminal: Array[Task[_]],
      outPath: os.Path
  ): Unit = {
    val (vertexToIndex, edgeIndices) =
      SpanningForest.graphMapToIndices(indexToTerminal, interGroupDeps)

    SpanningForest.writeJsonFile(
      outPath / OutFiles.millDependencyTree,
      edgeIndices,
      indexToTerminal.indices.toSet,
      indexToTerminal(_).toString
    )
  }
  def logInvalidationTree(
      interGroupDeps: Map[Task[_], Seq[Task[_]]],
      indexToTerminal: Array[Task[_]],
      outPath: os.Path,
      uncached: ConcurrentHashMap[Task[_], Unit],
      changedValueHash: ConcurrentHashMap[Task[_], Unit]
  ): Unit = {
    val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)

    val changedTerminalIndices = changedValueHash.keys().asScala.toSet

    val (vertexToIndex, downstreamIndexEdges) = SpanningForest.graphMapToIndices(
      indexToTerminal,
      reverseInterGroupDeps.filterKeys(changedTerminalIndices).toMap
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
          val uncachedIndex = vertexToIndex(uncachedTask)
          Option.when(
            // Filter out input and source tasks which do not cause downstream invalidations
            // from the invalidation tree, because most of them are un-interesting and the
            // user really only cares about (a) inputs that cause downstream tasks to invalidate
            // or (b) non-input tasks that were invalidated alone (e.g. due to a codesig change)
            !uncachedTask.isInstanceOf[InputImpl[_]] || edgeSourceIndices(uncachedIndex)
          ) {
            uncachedIndex
          }
        }
        .toSet,
      indexToTerminal(_).toString
    )
  }
}
