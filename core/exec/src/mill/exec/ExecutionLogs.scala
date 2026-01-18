package mill.exec

import mill.constants.OutFiles.OutFiles
import mill.api.Task
import mill.internal.SpanningForest

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
      indexToTerminal: Array[Task[?]],
      outPath: os.Path,
      uncached: ConcurrentHashMap[Task[?], Unit],
      changedValueHash: ConcurrentHashMap[Task[?], Unit],
      // JSON string to avoid classloader issues when crossing classloader boundaries
      spanningInvalidationTree: Option[String]
  ): Unit = {
    val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)

    val changedTerminalIndices = changedValueHash.keys().asScala.toSet

    val (vertexToIndex, downstreamIndexEdges) = SpanningForest.graphMapToIndices(
      indexToTerminal,
      reverseInterGroupDeps.view.filterKeys(changedTerminalIndices).toMap
    )

    val edgeSourceIndices = downstreamIndexEdges
      .zipWithIndex
      .collect { case (es, i) if es.nonEmpty => i }
      .toSet

    val interestingIndices = uncached.keys().asScala
      .flatMap { uncachedTask =>
        val uncachedIndex = vertexToIndex(uncachedTask)
        Option.when(
          // Filter out input and source tasks which do not cause downstream invalidations
          // from the invalidation tree, because most of them are un-interesting and the
          // user really only cares about (a) inputs that cause downstream tasks to invalidate
          // or (b) non-input tasks that were invalidated alone (e.g. due to a codesig change)
          !uncachedTask.isInstanceOf[Task.Input[?]] || edgeSourceIndices(uncachedIndex)
        ) {
          uncachedIndex
        }
      }
      .toSet

    // Generate the base invalidation tree
    val baseTree = SpanningForest.writeJson(
      downstreamIndexEdges,
      interestingIndices,
      indexToTerminal(_).toString
    )

    // Merge with the code signature spanning tree if available
    // Parse the JSON string to ujson.Obj (parsing in local classloader avoids classloader issues)
    val finalTree = spanningInvalidationTree.flatMap { jsonStr =>
      try {
        ujson.read(jsonStr) match {
          case obj: ujson.Obj if obj.value.nonEmpty => Some(obj)
          case _ => None
        }
      } catch {
        case _: Exception => None
      }
    } match {
      case Some(spanningTree) => SpanningForest.mergeCodeSignatureTree(baseTree, spanningTree)
      case None => baseTree
    }

    os.write.over(outPath / OutFiles.millInvalidationTree, finalTree.render(indent = 2))
  }
}
