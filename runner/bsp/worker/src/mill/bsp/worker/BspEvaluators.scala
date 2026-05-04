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

/**
 * Manages BSP module discovery and lookup for a Mill build.
 * Coordinates between evaluators, module graphs, and script discovery.
 */
class BspEvaluators(
    workspaceDir: os.Path,
    val evaluators: Seq[EvaluatorApi],
    debug: (() => String) => Unit
) {
  import BspEvaluators.*

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

  lazy val nonScriptSources: Seq[os.SubPath] = extractInputPaths(_.bspBuildTargetSources)
  lazy val nonScriptResources: Seq[os.SubPath] = extractInputPaths(_.bspBuildTargetResources)
  lazy val bspScriptIgnore: Seq[String] = MillBuildRootModuleApi.bspScriptIgnore(evaluators)

  private lazy val snapshot: Snapshot = {
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

    val modulesIdList = bspModulesIdList0 ++ scriptModules
    val modulesById = modulesIdList.toMap
    debug(() => s"BspModules: ${modulesById.view.mapValues(_._1.bspDisplayName).toMap}")
    val bspIdByModule = modulesById.view.mapValues(_._1).map(_.swap).toMap
    val targetSnapshots = modulesIdList.map { case (id, (module, _)) =>
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
        targetDigest = (module.bspBuildTarget, dependencyUris).##
      )
    }
    Snapshot(modulesIdList, modulesById, targetSnapshots, bspIdByModule)
  }

  lazy val bspModulesIdList: Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] =
    snapshot.modulesIdList

  lazy val bspModulesById: Map[BuildTargetIdentifier, (BspModuleApi, EvaluatorApi)] =
    snapshot.modulesById

  lazy val targetSnapshots: Seq[ChangeNotifier.TargetSnapshot] =
    snapshot.targetSnapshots

  lazy val rootModules: Seq[BaseModuleApi] = evaluators.map(_.rootModule)

  lazy val bspIdByModule: Map[BspModuleApi, BuildTargetIdentifier] =
    snapshot.bspIdByModule
  lazy val syntheticRootBspBuildTarget: Option[SyntheticRootBspBuildTargetData] =
    Some(SyntheticRootBspBuildTargetData.make(workspaceDir))

  def filterNonSynthetic(input: java.util.List[BuildTargetIdentifier])
      : java.util.List[BuildTargetIdentifier] = {
    import scala.jdk.CollectionConverters.*
    val syntheticIds = syntheticRootBspBuildTarget.map(_.id).toSet
    input.asScala.filterNot(syntheticIds.contains).toList.asJava
  }
}

object BspEvaluators {
  private case class Snapshot(
      modulesIdList: Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))],
      modulesById: Map[BuildTargetIdentifier, (BspModuleApi, EvaluatorApi)],
      targetSnapshots: Seq[ChangeNotifier.TargetSnapshot],
      bspIdByModule: Map[BspModuleApi, BuildTargetIdentifier]
  )
}
