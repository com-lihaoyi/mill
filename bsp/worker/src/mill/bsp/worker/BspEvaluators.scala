package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.runner.api.{BaseModuleApi, BspModuleApi, EvaluatorApi, ModuleApi}

private class BspEvaluators(
    workspaceDir: os.Path,
    evaluators: Seq[EvaluatorApi],
    debug: (() => String) => Unit
) {

  /**
   * Compute all transitive modules from module children and via moduleDeps + compileModuleDeps
   */
  def transitiveModules(module: ModuleApi): Seq[ModuleApi] = {
    Seq(module) ++ module.moduleDirectChildren.flatMap(transitiveModules)
  }

  lazy val bspModulesIdList: Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] = {
    val modules: Seq[(ModuleApi, Seq[ModuleApi], EvaluatorApi)] = evaluators
      .map(ev => (ev.rootModule, transitiveModules(ev.rootModule), ev))

    modules
      .flatMap { case (rootModule, modules, eval) =>
        modules.collect {
          case m: BspModuleApi =>
            val uri = Utils.sanitizeUri(
              (os.Path(rootModule.moduleDirJava) / m.moduleSegments.parts).toNIO
            )

            (new BuildTargetIdentifier(uri), (m, eval))
        }
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
    SyntheticRootBspBuildTargetData.makeIfNeeded(bspModulesById.values.map(_._1), workspaceDir)

  def filterNonSynthetic(input: java.util.List[BuildTargetIdentifier])
      : java.util.List[BuildTargetIdentifier] = {
    import scala.jdk.CollectionConverters.*
    input.asScala.filterNot(syntheticRootBspBuildTarget.map(_.id).contains).toList.asJava
  }
}
