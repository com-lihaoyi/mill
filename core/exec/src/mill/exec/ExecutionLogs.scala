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
      spanningInvalidationTree: Option[String],
      millVersionChanged: Option[(String, String)],
      millJvmVersionChanged: Option[(String, String)]
  ): Unit = {
    val reverseInterGroupDeps = SpanningForest.reverseEdges(interGroupDeps)
    val changedTerminals = changedValueHash.keys().asScala.toSet

    // Build task edges map (task name -> downstream task names)
    val taskEdges: Map[String, Seq[String]] = reverseInterGroupDeps
      .view
      .filterKeys(changedTerminals)
      .map { case (k, vs) => k.toString -> vs.map(_.toString) }
      .toMap

    // Find interesting tasks: uncached tasks that either cause downstream invalidations
    // or are non-input tasks (e.g. invalidated due to codesig change)
    val downstreamSources = taskEdges.filter(_._2.nonEmpty).keySet
    val interestingTasks = uncached.keys().asScala
      .filter { task =>
        !task.isInstanceOf[Task.Input[?]] || downstreamSources.contains(task.toString)
      }
      .map(_.toString)
      .toSet

    val finalTree = SpanningForest.buildInvalidationTree(
      taskEdges = taskEdges,
      interestingTasks = interestingTasks,
      codeSignatureTree = spanningInvalidationTree,
      millVersionChanged = millVersionChanged,
      millJvmVersionChanged = millJvmVersionChanged
    )

    os.write.over(outPath / OutFiles.millInvalidationTree, finalTree.render(indent = 2))
  }
}
