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
      transitiveNamed: Seq[Task.Named[?]],
      // JSON string to avoid classloader issues when crossing classloader boundaries
      spanningInvalidationTree: Option[String] = None,
      previousVersions: Option[VersionState] = None
  ): Unit = {
    val finalTree = InvalidationForest.buildInvalidationTree(
      interGroupDeps = interGroupDeps,
      transitiveNamed = transitiveNamed,
      uncachedTasks = Some(uncached.keys().asScala.toSet),
      edgeFilter = Some(changedValueHash.keys().asScala.toSet),
      codeSignatureTree = spanningInvalidationTree,
      previousVersions = previousVersions
    )

    os.write.over(outPath / OutFiles.millInvalidationTree, finalTree.render(indent = 2))
  }
}
