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
    val watched: Seq[Watchable]
) {

  private lazy val disabledBspModules: Set[ModuleApi] =
    Utils.computeDisabledBspModules(evaluators)

  private def moduleUri(rootModule: ModuleApi, module: ModuleApi) = Utils.sanitizeUri(
    (os.Path(rootModule.moduleDirJava) / module.moduleSegments.parts).toNIO
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
