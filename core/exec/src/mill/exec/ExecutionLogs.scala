package mill.exec

import mill.api.daemon.internal.LauncherOutFiles
import mill.api.Task
import mill.internal.{InvalidationForest, SpanningForest}

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.unused
import scala.jdk.CollectionConverters.EnumerationHasAsScala

private object ExecutionLogs {
  def logDependencyTree(
      interGroupDeps: Map[Task[?], Seq[Task[?]]],
      indexToTerminal: Array[Task[?]],
      @unused outPath: os.Path,
      runArtifacts: LauncherOutFiles
  ): Unit = {
    val dependencyTreePath = os.Path(runArtifacts.dependencyTree)
    val ( /*vertexToIndex*/ _, edgeIndices) =
      SpanningForest.graphMapToIndices(indexToTerminal, interGroupDeps)

    SpanningForest.writeJsonFile(
      dependencyTreePath,
      edgeIndices,
      indexToTerminal.indices.toSet,
      indexToTerminal(_).toString
    )
  }
  def logInvalidationTree(
      interGroupDeps: Map[Task[?], Seq[Task[?]]],
      @unused outPath: os.Path,
      runArtifacts: LauncherOutFiles,
      uncached: ConcurrentHashMap[Task[?], Unit],
      changedValueHash: ConcurrentHashMap[Task[?], Unit],
      // JSON string to avoid classloader issues when crossing classloader boundaries
      spanningInvalidationTree: Option[String] = None,
      // Per-task invalidation reasons (e.g., version mismatch reasons)
      taskInvalidationReasons: Map[String, String] = Map.empty
  ): Unit = {
    val invalidationTreePath = os.Path(runArtifacts.invalidationTree)
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
      taskInvalidationReasons = taskInvalidationReasons
    )

    os.write.over(
      invalidationTreePath,
      finalTree.render(indent = 2)
    )
  }
}
