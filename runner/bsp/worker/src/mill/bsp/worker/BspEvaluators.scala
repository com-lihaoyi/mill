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

/**
 * Manages BSP module discovery and lookup for a Mill build.
 * Coordinates between evaluators, module graphs, and script discovery.
 */
class BspEvaluators(
    workspaceDir: os.Path,
    val evaluators: Seq[EvaluatorApi],
    debug: (() => String) => Unit,
    val watched: Seq[Watchable],
    val bootstrapErrorOpt: Option[String] = None
) {
  def this(
      workspaceDir: os.Path,
      evaluators: Seq[EvaluatorApi],
      debug: (() => String) => Unit,
      watched: Seq[Watchable]
  ) = this(workspaceDir, evaluators, debug, watched, None)

  private lazy val disabledBspModules: Set[ModuleApi] =
    Utils.computeDisabledBspModules(evaluators)

  // Strip trailing `/` and `:` from segment parts, since external modules and script
  // modules use those suffixes which are invalid os.Path characters
  // https://github.com/com-lihaoyi/mill/issues/6925
  private def moduleUri(rootModule: ModuleApi, module: ModuleApi) = Utils.sanitizeUri(
    (os.Path(rootModule.moduleDirJava) / module.moduleSegments.parts.map(
      _.stripSuffix("/").stripSuffix(":")
    )).toNIO
  )

  lazy val bspModulesIdList0: Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] =
    for {
      eval <- evaluators
      bspModule <- Utils.transitiveModules(eval.rootModule).collect { case m: BspModuleApi => m }
      uri = moduleUri(eval.rootModule, bspModule)
      disabled = disabledBspModules.contains(bspModule)
      _ = if (disabled) eval.baseLogger.info(s"BSP disabled for target $uri")
      if !disabled
    } yield (new BuildTargetIdentifier(uri), (bspModule, eval))

  /**
   * Extract paths from input task results by traversing task graphs to find Task.Input roots,
   * evaluating only those inputs, and extracting PathRef values.
   */
  private def extractInputPaths(taskSelector: BspJavaModuleApi => TaskApi[?]): Seq[os.SubPath] = {
    evaluators
      .flatMap { ev =>
        val tasks = Utils.transitiveModules(ev.rootModule)
          .collect { case m: JavaModuleApi => taskSelector(m.bspJavaModule()) }

        Utils.findInputTasks(tasks) match {
          case Nil => Seq.empty
          case inputTasks => Utils.extractPathsFromResults(ev.executeApi(inputTasks).values.get)
        }
      }
      .map(_.subRelativeTo(workspaceDir))
  }

  val nonScriptSources = extractInputPaths(_.bspBuildTargetSources)
  val nonScriptResources = extractInputPaths(_.bspBuildTargetResources)
  val bspScriptIgnore: Seq[String] = MillBuildRootModuleApi.bspScriptIgnore(evaluators)

  lazy val bspModulesIdList: Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] = {
    val scriptModules = evaluators.headOption
      .map(eval =>
        ScriptModuleDiscovery.discover(
          eval,
          bspScriptIgnore,
          nonScriptSources,
          nonScriptResources,
          debug
        )
      )
      .getOrElse(Seq.empty)

    bspModulesIdList0 ++ scriptModules
  }

  lazy val bspModulesById: Map[BuildTargetIdentifier, (BspModuleApi, EvaluatorApi)] = {
    val map = bspModulesIdList.toMap
    debug(() => s"BspModules: ${map.view.mapValues(_._1.bspDisplayName).toMap}")
    map
  }

  lazy val targetSnapshots: Seq[ChangeNotifier.TargetSnapshot] =
    bspModulesIdList.map { case (id, (module, ev)) =>
      val dependencyUris = module match {
        case jm: JavaModuleApi =>
          (jm.recursiveModuleDeps ++ jm.compileModuleDepsChecked)
            .distinct
            .collect { case bm: BspModuleApi => bm }
            .flatMap(bm => bspIdByModule.get(bm).map(_.getUri))
            .sorted
        case _ => Nil
      }

      ChangeNotifier.TargetSnapshot(
        id = id,
        buildTarget = module.bspBuildTarget,
        dependencyUris = dependencyUris,
        classLoader = ev.rootModule.getClass.getClassLoader
      )
    }

  lazy val rootModules: Seq[BaseModuleApi] = evaluators.map(_.rootModule)

  lazy val bspIdByModule: Map[BspModuleApi, BuildTargetIdentifier] =
    bspModulesById.view.mapValues(_._1).map(_.swap).toMap
  lazy val syntheticRootBspBuildTarget: Option[SyntheticRootBspBuildTargetData] =
    Some(SyntheticRootBspBuildTargetData.make(workspaceDir))

  def filterNonSynthetic(input: java.util.List[BuildTargetIdentifier])
      : java.util.List[BuildTargetIdentifier] = {
    import scala.jdk.CollectionConverters.*
    val syntheticIds = syntheticRootBspBuildTarget.map(_.id).toSet
    input.asScala.filterNot(syntheticIds.contains).toList.asJava
  }
}
