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
      changedValueHash: ConcurrentHashMap[Task[?], Unit]
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

    // Try to read the code signature spanning tree to add def/call chain context
    val spanningTreePath =
      outPath / "mill-build" / "codeSignatures.dest" / "current" / "spanningInvalidationTree.json"

    val finalTree = if (os.exists(spanningTreePath)) {
      try {
        val spanningTree = ujson.read(os.read(spanningTreePath))
        // Only merge if the spanning tree is non-empty (has actual code signature changes)
        spanningTree match {
          case obj: ujson.Obj if obj.value.nonEmpty => mergeCodeSignatureTree(baseTree, spanningTree)
          case _ => baseTree
        }
      } catch {
        case _: Exception => baseTree
      }
    } else baseTree

    os.write.over(outPath / OutFiles.millInvalidationTree, finalTree.render(indent = 2))
  }

  /**
   * Merges the code signature spanning tree with the task invalidation tree.
   * Groups tasks by their code path and wraps each group together to preserve
   * the task dependency hierarchy.
   *
   * Only merges if the base tree represents code-change invalidation (flat structure
   * where all tasks are at root level with empty children). Nested structure indicates
   * source/input changes where the spanning tree is irrelevant.
   */
  private def mergeCodeSignatureTree(baseTree: ujson.Obj, spanningTree: ujson.Value): ujson.Obj = {
    // Check if base tree has nested structure (non-code-change invalidation)
    // If any root task has non-empty children, it means tasks were invalidated via
    // dependency chains (source/input changes), not by code changes
    val hasNestedStructure = baseTree.value.exists { case (_, subtree) =>
      subtree match {
        case obj: ujson.Obj => obj.value.nonEmpty
        case _ => false
      }
    }

    if (hasNestedStructure) {
      // Don't merge - this is a source/input change, not a code change
      return baseTree
    }

    val result = ujson.Obj()

    // Group tasks by their code path
    val tasksByPath = collection.mutable.Map[Seq[String], collection.mutable.Buffer[(String, ujson.Value)]]()
    val tasksWithoutPath = collection.mutable.Buffer[(String, ujson.Value)]()

    for ((taskName, subtree) <- baseTree.value) {
      findPathForTask(taskName, spanningTree) match {
        case Some(path) if path.nonEmpty =>
          tasksByPath.getOrElseUpdate(path, collection.mutable.Buffer()) += ((taskName, subtree))
        case _ =>
          tasksWithoutPath += ((taskName, subtree))
      }
    }

    // For each unique code path, create a single entry with all tasks underneath
    for ((path, tasks) <- tasksByPath) {
      // Combine all tasks into a single object
      val combinedTasks = ujson.Obj()
      for ((taskName, subtree) <- tasks) {
        combinedTasks(taskName) = subtree
      }

      // Wrap the combined tasks with the code path
      val wrappedTree = wrapWithPath(path, combinedTasks)

      // Merge into result
      wrappedTree match {
        case obj: ujson.Obj =>
          for ((k, v) <- obj.value) {
            result.value.get(k) match {
              case Some(existing: ujson.Obj) =>
                v match {
                  case vObj: ujson.Obj => deepMerge(existing, vObj)
                  case _ => result(k) = v
                }
              case _ => result(k) = v
            }
          }
        case _ => // shouldn't happen
      }
    }

    // Add tasks without code path directly
    for ((taskName, subtree) <- tasksWithoutPath) {
      result(taskName) = subtree
    }

    result
  }

  /**
   * Finds the path of def/call nodes in the spanning tree that leads to a
   * method signature matching the given task name.
   *
   * Task names like "foo.compile" can match:
   * 1. Task method signatures like "def build_.package_$foo$#compile()..."
   * 2. Module method signatures like "def build_.package_#foo()..." when the
   *    module constructor was changed
   *
   * We find the deepest matching method in the tree.
   */
  private def findPathForTask(taskName: String, tree: ujson.Value): Option[Seq[String]] = {
    val parts = taskName.split('.')
    if (parts.isEmpty) return None

    val taskMethod = parts.last
    val moduleParts = parts.init

    // Build patterns to match method signatures
    // For "foo.compile", we look for:
    // 1. #compile( in a class containing $foo$
    // 2. #foo() for the module definition itself
    val lastModuleName = if (moduleParts.nonEmpty) moduleParts.last else taskMethod

    def matchesTask(key: String): Boolean = {
      if (!key.startsWith("def ")) return false

      // Match task method: contains the module class and task method name
      val matchesTaskMethod = key.contains(s"#$taskMethod(") ||
        key.contains(s"#$taskMethod$$")

      // Match module method: ends with #moduleName()
      val matchesModuleMethod = key.contains(s"#$lastModuleName()")

      // Also check for the module class pattern
      val moduleClassPattern = moduleParts.map(p => s"$$${p}$$").mkString
      val containsModuleClass = moduleClassPattern.isEmpty || key.contains(moduleClassPattern)

      (matchesTaskMethod || matchesModuleMethod) && containsModuleClass
    }

    // Find the deepest matching method in the tree
    def findDeepest(node: ujson.Value, currentPath: Seq[String]): Option[Seq[String]] = node match {
      case obj: ujson.Obj =>
        var deepestMatch: Option[Seq[String]] = None

        for ((key, value) <- obj.value) {
          val newPath = currentPath :+ key

          // Check if this key matches the task
          if (matchesTask(key)) {
            deepestMatch = Some(newPath)
          }

          // Recursively search children for deeper matches
          findDeepest(value, newPath) match {
            case Some(deeper) => deepestMatch = Some(deeper)
            case None => // keep current match
          }
        }

        deepestMatch
      case _ => None
    }

    findDeepest(tree, Seq.empty)
  }

  /**
   * Wraps content with a def/call chain path.
   */
  private def wrapWithPath(path: Seq[String], content: ujson.Value): ujson.Value = {
    if (path.isEmpty) {
      content
    } else {
      // Build from inside out - the content goes at the innermost level
      var current: ujson.Value = content
      for (key <- path.reverse) {
        current = ujson.Obj(key -> current)
      }
      current
    }
  }

  /**
   * Deep merges two ujson.Obj trees.
   */
  private def deepMerge(target: ujson.Obj, source: ujson.Obj): Unit = {
    for ((k, v) <- source.value) {
      target.value.get(k) match {
        case Some(existing: ujson.Obj) =>
          v match {
            case vObj: ujson.Obj => deepMerge(existing, vObj)
            case _ => target(k) = v
          }
        case _ => target(k) = v
      }
    }
  }
}
