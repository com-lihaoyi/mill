package mill.exec

import mill.constants.OutFiles.OutFiles
import mill.api.Task
import mill.api.daemon.VersionState
import mill.internal.{InvalidationForest, SpanningForest}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.EnumerationHasAsScala

private object ExecutionLogs {
  def logDependencyTree(
      interGroupDeps: Map[Task[?], Seq[Task[?]]],
      indexToTerminal: Array[Task[?]],
      outPath: os.Path
  ): Unit = {
    val ( /*vertexToIndex*/ _, edgeIndices) =
      SpanningForest.graphMapToIndices(indexToTerminal, interGroupDeps)

    SpanningForest.writeJsonFile(
      outPath / OutFiles.millDependencyTree,
      edgeIndices,
      indexToTerminal.indices.toSet,
      indexToTerminal(_).toString
    )
  }
  def logInvalidationTree(
      interGroupDeps: Map[Task[?], Seq[Task[?]]],
      outPath: os.Path,
      uncached: ConcurrentHashMap[Task[?], Unit],
      changedValueHash: ConcurrentHashMap[Task[?], Unit],
      // JSON string to avoid classloader issues when crossing classloader boundaries
      spanningInvalidationTree: Option[String] = None,
      previousVersions: Option[VersionState] = None
  ): Unit = {
    val changedTasks = changedValueHash.keys().asScala.toSet
    val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)
    val filteredReverseInterGroupDeps = reverseInterGroupDeps.view.filterKeys(changedTasks).toMap
    val downstreamSources = filteredReverseInterGroupDeps.filter(_._2.nonEmpty).keySet

    // Root invalidated tasks: uncached tasks that either cause downstream invalidations
    // or are non-input tasks (e.g. invalidated due to codesig change)
    val rootInvalidatedTasks = uncached.keys().asScala
      .filter(task => !task.isInstanceOf[Task.Input[?]] || downstreamSources.contains(task))
      .toSet

    val finalTree = InvalidationForest.buildInvalidationTree(
      upstreamTaskEdges0 = interGroupDeps,
      rootInvalidatedTasks = rootInvalidatedTasks,
      codeSignatureTree = spanningInvalidationTree,
      previousVersions = previousVersions
    )

    os.write.over(outPath / OutFiles.millInvalidationTree, finalTree.render(indent = 2))
  }
}
