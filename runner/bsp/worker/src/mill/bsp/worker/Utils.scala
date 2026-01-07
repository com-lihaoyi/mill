package mill.bsp.worker

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.{
  BuildClient,
  BuildTarget,
  BuildTargetCapabilities,
  BuildTargetIdentifier,
  OutputPathItem,
  OutputPathItemKind,
  StatusCode,
  TaskId
}
import mill.api.ExecResult.{Skipped, Success}
import mill.api.daemon.internal.{ExecutionResultsApi, EvaluatorApi, JavaModuleApi, ModuleApi, PathRefApi, TaskApi}
import mill.api.daemon.internal.bsp.{BspBuildTarget, BspModuleApi, JvmBuildTarget}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.chaining.scalaUtilChainingOps

object Utils {

  def sanitizeUri(uri: String): String =
    if (uri.endsWith("/")) sanitizeUri(uri.substring(0, uri.length - 1)) else uri

  def sanitizeUri(uri: java.nio.file.Path): String = sanitizeUri(uri.toUri.toString)

  // define the function that spawns compilation reporter for each module based on the
  // module's hash code TODO: find something more reliable than the hash code
  def getBspLoggedReporterPool(
      originId: String,
      bspIdsByModule: Map[BspModuleApi, BuildTargetIdentifier],
      client: BuildClient
  ): Int => Option[BspCompileProblemReporter] = { (moduleHashCode: Int) =>
    bspIdsByModule.find(_._1.hashCode == moduleHashCode).map {
      case (module, targetId) =>
        val buildTarget = module.bspBuildTarget
        val taskId = new TaskId(module.hashCode.toString)
        new BspCompileProblemReporter(
          client,
          targetId,
          buildTarget.displayName.getOrElse(targetId.getUri),
          taskId,
          Option(originId)
        )
    }
  }

  // Get the execution status code given the results from Evaluator.evaluate
  def getStatusCode(resultsLists: Seq[ExecutionResultsApi]): StatusCode = {
    val statusCodes =
      resultsLists.flatMap(r =>
        r.transitiveResultsApi.keys.map(task => getStatusCodePerTask(r, task)).toSeq
      )
    if (statusCodes.contains(StatusCode.ERROR)) StatusCode.ERROR
    else if (statusCodes.contains(StatusCode.CANCELLED)) StatusCode.CANCELLED
    else StatusCode.OK
  }

  def makeBuildTarget(
      id: BuildTargetIdentifier,
      depsIds: Seq[BuildTargetIdentifier],
      bt: BspBuildTarget,
      data: Option[(String, Object)]
  ): BuildTarget = {
    val buildTarget = new BuildTarget(
      id,
      bt.tags.asJava,
      bt.languageIds.asJava,
      depsIds.asJava,
      new BuildTargetCapabilities().tap { it =>
        it.setCanCompile(bt.canCompile)
        it.setCanTest(bt.canTest)
        it.setCanRun(bt.canRun)
        it.setCanDebug(bt.canDebug)
      }
    )

    bt.displayName.foreach(buildTarget.setDisplayName)
    bt.baseDirectory.foreach(p => buildTarget.setBaseDirectory(sanitizeUri(p)))

    for ((dataKind, data) <- data) {
      buildTarget.setDataKind(dataKind)
      buildTarget.setData(data)
    }
    buildTarget
  }

  def outputPaths(
      buildTargetBaseDir: os.Path,
      topLevelProjectRoot: os.Path
  ): Seq[OutputPathItem] = {

    def outputPathItem(path: os.Path) =
      // Spec says, a directory must end with a forward slash
      new OutputPathItem(sanitizeUri(path.toNIO) + "/", OutputPathItemKind.DIRECTORY)

    if (topLevelProjectRoot.startsWith(buildTargetBaseDir))
      Seq(
        outputPathItem(topLevelProjectRoot / ".idea"),
        outputPathItem(topLevelProjectRoot / "out"),
        outputPathItem(topLevelProjectRoot / ".bsp"),
        outputPathItem(topLevelProjectRoot / ".bloop"),

        // All Eclipse JDT related project files (likely generated)
        outputPathItem(topLevelProjectRoot / ".project"),
        outputPathItem(topLevelProjectRoot / ".classpath"),
        outputPathItem(topLevelProjectRoot / ".settings")
      )
    else Nil
  }

  private def getStatusCodePerTask(
      results: ExecutionResultsApi,
      task: TaskApi[?]
  ): StatusCode = {
    results.transitiveResultsApi(task) match {
      case Success(_) => StatusCode.OK
      case Skipped => StatusCode.CANCELLED
      case _ => StatusCode.ERROR
    }
  }

  /**
   * Same as Iterable.groupMap, but returns a sequence instead of a map, and preserves
   * the order of appearance of the keys from the input sequence
   */
  def groupList[A, K, B](seq: collection.Seq[A])(key: A => K)(f: A => B): Seq[(K, Seq[B])] = {
    val map = new mutable.HashMap[K, mutable.ListBuffer[B]]
    val list = new mutable.ListBuffer[(K, mutable.ListBuffer[B])]
    for (a <- seq) {
      val k = key(a)
      val b = f(a)
      val l = map.getOrElseUpdate(
        k, {
          val buf = mutable.ListBuffer[B]()
          list.append((k, buf))
          buf
        }
      )
      l.append(b)
    }
    list
      .iterator
      .map { case (k, l) => (k, l.result()) }
      .toList
  }

  def jvmBuildTarget(d: JvmBuildTarget): bsp4j.JvmBuildTarget =
    new bsp4j.JvmBuildTarget().tap { it =>
      d.javaHome.foreach(jh => it.setJavaHome(jh.uri))
      d.javaVersion.foreach(jv => it.setJavaVersion(jv))
    }

  /**
   * Combines two status codes, returning the most severe.
   * ERROR > CANCELLED > OK
   */
  def combineStatusCodes(a: StatusCode, b: StatusCode): StatusCode = (a, b) match {
    case (StatusCode.ERROR, _) | (_, StatusCode.ERROR) => StatusCode.ERROR
    case (StatusCode.CANCELLED, _) | (_, StatusCode.CANCELLED) => StatusCode.CANCELLED
    case _ => StatusCode.OK
  }

  // ===========================================================================
  // Task Graph Utilities
  // ===========================================================================

  /**
   * Find all input tasks (Task.Input, Task.Source, Task.Sources) at the roots of the task graph
   * by traversing upstream from the given tasks.
   */
  def findInputTasks(tasks: Seq[TaskApi[?]]): Seq[TaskApi[?]] = {
    val visited = collection.mutable.Set.empty[TaskApi[?]]
    val inputTasks = collection.mutable.ListBuffer.empty[TaskApi[?]]
    val queue = collection.mutable.Queue.empty[TaskApi[?]]

    tasks.foreach(queue.enqueue(_))

    while (queue.nonEmpty) {
      val current = queue.dequeue()
      if (!visited.contains(current)) {
        visited.add(current)
        if (current.isInputTask) inputTasks += current
        else current.inputsApi.foreach(queue.enqueue(_))
      }
    }

    inputTasks.toSeq
  }

  /**
   * Extract os.Path values from input task results. Input tasks like Task.Sources return
   * PathRef or Seq[PathRef] values.
   */
  def extractPathsFromResults(results: Seq[Any]): Seq[os.Path] = {
    results.flatMap {
      case pathRef: PathRefApi => Seq(os.Path(pathRef.javaPath))
      case pathRefs: Seq[?] => pathRefs.collect { case pr: PathRefApi => os.Path(pr.javaPath) }
      case _ => Seq.empty
    }
  }

  // ===========================================================================
  // Module Graph Utilities
  // ===========================================================================

  /**
   * Compute all transitive modules from module children via moduleDirectChildren
   */
  def transitiveModules(module: ModuleApi): Seq[ModuleApi] = {
    Seq(module) ++ module.moduleDirectChildren.flatMap(transitiveModules)
  }

  /**
   * Compute modules with BSP transitively disabled using single-pass topological traversal.
   * A module is disabled if it has enableBsp=false OR any of its direct deps is disabled.
   */
  def computeDisabledBspModules(evaluators: Seq[EvaluatorApi]): Set[ModuleApi] = {
    import mill.api.daemon.internal.bsp.BspModuleApi

    val allModules = evaluators.flatMap(ev => transitiveModules(ev.rootModule))
    val moduleToIndex = allModules.zipWithIndex.toMap
    val indexToModule = allModules.toIndexedSeq

    val graph: IndexedSeq[Array[Int]] = indexToModule.map { m =>
      val deps = m match {
        case jm: JavaModuleApi => jm.moduleDepsChecked ++ jm.compileModuleDepsChecked
        case _ => Nil
      }
      deps.flatMap(moduleToIndex.get).toArray
    }

    val disabled = collection.mutable.Set.empty[Int]

    // Module graph cannot have cycles so each SCC must be of size 1
    for (Array(idx) <- mill.internal.Tarjans(graph).reverse) {
      val module = indexToModule(idx)
      val isDirect = module match {
        case bsp: BspModuleApi => !bsp.enableBsp
        case _ => false
      }
      if (isDirect || graph(idx).exists(disabled.contains)) disabled.add(idx)
    }

    disabled.iterator.map(indexToModule).toSet
  }

}
