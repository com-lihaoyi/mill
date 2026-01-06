package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.api.daemon.internal.bsp.BspModuleApi
import mill.api.daemon.internal.{
  BaseModuleApi,
  EvaluatorApi,
  JavaModuleApi,
  MillBuildRootModuleApi,
  ModuleApi,
  TaskApi
}
import mill.api.daemon.internal.bsp.BspJavaModuleApi
import mill.api.daemon.Watchable
import org.eclipse.jgit.ignore.{FastIgnoreRule, IgnoreNode}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

class BspEvaluators(
    workspaceDir: os.Path,
    val evaluators: Seq[EvaluatorApi],
    debug: (() => String) => Unit,
    val watched: Seq[Watchable]
) {

  /**
   * Compute all transitive modules from module children via moduleDirectChildren
   */
  def transitiveModules(module: ModuleApi): Seq[ModuleApi] = {
    Seq(module) ++ module.moduleDirectChildren.flatMap(transitiveModules)
  }

  // Compute modules with BSP transitively disabled using single-pass topological traversal.
  // A module is disabled if it has enableBsp=false OR any of its direct deps is disabled.
  private lazy val disabledBspModules: Set[ModuleApi] = {
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
    for (Array(idx) <- mill.internal.Tarjans(graph).reverse){
      val module = indexToModule(idx)
      val isDirect = module match {
        case bsp: BspModuleApi => !bsp.enableBsp
        case _ => false
      }
      if (isDirect || graph(idx).exists(disabled.contains)) disabled.add(idx)
    }

    disabled.iterator.map(indexToModule).toSet
  }

  private def moduleUri(rootModule: ModuleApi, module: ModuleApi) = Utils.sanitizeUri(
    (os.Path(rootModule.moduleDirJava) / module.moduleSegments.parts).toNIO
  )

  lazy val bspModulesIdList0: Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] =
    for {
      eval <- evaluators
      bspModule <- transitiveModules(eval.rootModule).collect { case m: BspModuleApi => m }
      uri = moduleUri(eval.rootModule, bspModule)
      disabled = disabledBspModules.contains(bspModule)
      _ = if (disabled) eval.baseLogger.info(s"BSP disabled for target $uri")
      if !disabled
    } yield (new BuildTargetIdentifier(uri), (bspModule, eval))

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
      case pathRef: mill.api.PathRef => Seq(pathRef.path)
      case pathRefs: Seq[?] =>
        pathRefs.collect { case pr: mill.api.PathRef => pr.path }
      case _ => Seq.empty
    }
  }

  /**
   * Extract paths from input task results by traversing task graphs to find Task.Input roots,
   * evaluating only those inputs, and extracting PathRef values.
   */
  private def extractInputPaths(
      taskSelector: BspJavaModuleApi => TaskApi[?]
  ): Seq[os.SubPath] = {
    evaluators.flatMap { ev =>
      val tasks = transitiveModules(ev.rootModule)
        .collect { case m: JavaModuleApi => taskSelector(m.bspJavaModule()) }

      findInputTasks(tasks) match {
        case Nil => Seq.empty
        case inputTasks =>
          extractPathsFromResults(ev.executeApi(inputTasks).values.get)
            .map(_.subRelativeTo(workspaceDir))
      }
    }
  }

  val nonScriptSources = extractInputPaths(_.bspBuildTargetSources)
  val nonScriptResources = extractInputPaths(_.bspBuildTargetResources)

  val bspScriptIgnore: Seq[String] = {
    // look for this in the first meta-build frame, which would be the meta-build configured
    // by a `//|` build header in the main `build.mill` file in the project root folder
    evaluators.lift(1).toSeq.flatMap { ev =>
      val bspScriptIgnore: Seq[TaskApi[Seq[String]]] =
        Seq(ev.rootModule).collect { case m: MillBuildRootModuleApi => m.bspScriptIgnoreAll }

      ev.executeApi(bspScriptIgnore)
        .values
        .get
        .flatMap { (sources: Seq[String]) => sources }

    }
  }

  lazy val bspModulesIdList: Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] = {
    // Add script modules
    val scriptModules = evaluators
      .headOption.map { eval =>
        val outDir = os.Path(eval.outPathJava)
        discoverAndInstantiateScriptModules(workspaceDir, outDir, eval)
      }
      .getOrElse(Seq.empty)

    bspModulesIdList0 ++ scriptModules
  }

  private def discoverAndInstantiateScriptModules(
      workspaceDir: os.Path,
      outDir: os.Path,
      eval: EvaluatorApi
  ): Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] = {
    // Create IgnoreNode from bspScriptIgnore patterns
    val ignoreRules = bspScriptIgnore
      .filter(l => !l.startsWith("#"))
      .map(pattern => (pattern, new FastIgnoreRule(pattern)))

    val ignoreNode = new IgnoreNode(ignoreRules.map(_._2).asJava)

    // Extract directory prefixes from negation patterns (patterns starting with !)
    // These directories need to be walked even if they're ignored, because they contain
    // negated (un-ignored) files
    val negationPatternDirs: Set[String] = bspScriptIgnore
      .collect { case s"!$withoutNegation" =>
        // Extract all parent directory paths
        val pathParts = withoutNegation.split('/').dropRight(1) // Remove filename
        if (pathParts.nonEmpty) pathParts.inits.map(_.mkString("/"))
        else Nil
      }
      .flatten
      .toSet

    // Helper function to recursively check if a path should be ignored
    def isPathIgnored(relativePath: String, isDirectory: Boolean): Option[String] = {
      val relativePath2 = os.SubPath(relativePath)
      def insideModuleSources = (
        nonScriptSources.find(relativePath2.startsWith(_)) orElse
          nonScriptResources.find(relativePath2.startsWith(_))
      ).map("Inside module source folder " + _)

      ignoreNode.isIgnored(relativePath, isDirectory) match {
        case IgnoreNode.MatchResult.IGNORED => Some("Ignored due to `bspScriptIgnore`")
        case IgnoreNode.MatchResult.NOT_IGNORED => None
        case IgnoreNode.MatchResult.CHECK_PARENT =>
          // No direct match, need to check if parent directory is ignored
          val parentPath = relativePath.split('/').dropRight(1).mkString("/")
          if (parentPath.isEmpty) None // root level, not ignored by default
          // if this is inside a module's sources, ignore it
          else insideModuleSources.orElse {
            isPathIgnored(parentPath, true) // recursively check parent
          }
        case _ => insideModuleSources
      }
    }

    // Create filter function that checks both files and directories
    val skipPath: (String, Boolean) => Boolean = { (relativePath, isDirectory) =>
      // If this is a directory that contained in negation patterns, don't skip it
      if (isDirectory && negationPatternDirs.contains(relativePath)) false
      else {
        isPathIgnored(relativePath, isDirectory) match {
          case None => false
          case Some(msg) =>
            println(s"Skipping script discovery in $relativePath: $msg")
            true
        }
      }
    }

    // Convert Scala function to Function2 for reflection (String, Boolean) => Boolean
    val function2Class = eval.getClass.getClassLoader.loadClass("scala.Function2")

    // Reflectively load and call `ScriptModuleInit.discoverAndInstantiateScriptModules`
    // from the evaluator's classloader
    val result = eval
      .scriptModuleInit
      .getClass
      .getMethod(
        "discoverAndInstantiateScriptModules",
        eval.getClass
          .getClassLoader
          .loadClass("mill.api.Evaluator"),
        function2Class
      )
      .invoke(eval.scriptModuleInit, eval, skipPath)
      .asInstanceOf[Seq[(java.nio.file.Path, mill.api.Result[BspModuleApi])]]

    result.flatMap {
      case (scriptPath: java.nio.file.Path, mill.api.Result.Success(module: BspModuleApi)) =>
        Some((new BuildTargetIdentifier(Utils.sanitizeUri(scriptPath)), (module, eval)))
      case (scriptPath: java.nio.file.Path, f: mill.api.Result.Failure) =>
        println(s"Failed to instantiate script module for BSP: $scriptPath failed with ${f.error}")
        None
    }
  }
  lazy val bspModulesById: Map[BuildTargetIdentifier, (BspModuleApi, EvaluatorApi)] = {
    val map = bspModulesIdList.toMap
    debug(() => s"BspModules: ${map.view.mapValues(_._1.bspDisplayName).toMap}")
    map
  }

  lazy val rootModules: Seq[BaseModuleApi] = evaluators.map(_.rootModule)

  lazy val bspIdByModule: Map[BspModuleApi, BuildTargetIdentifier] =
    bspModulesById.view.mapValues(_._1).map(_.swap).toMap
  lazy val syntheticRootBspBuildTarget: Option[SyntheticRootBspBuildTargetData] =
    SyntheticRootBspBuildTargetData.makeIfNeeded(workspaceDir)

  def filterNonSynthetic(input: java.util.List[BuildTargetIdentifier])
      : java.util.List[BuildTargetIdentifier] = {
    import scala.jdk.CollectionConverters.*
    val syntheticIds = syntheticRootBspBuildTarget.map(_.id).toSet
    input.asScala.filterNot(syntheticIds.contains).toList.asJava
  }
}
