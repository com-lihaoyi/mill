package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.api.daemon.internal.bsp.BspModuleApi
import mill.api.daemon.internal.{BaseModuleApi, EvaluatorApi, ModuleApi}
import mill.api.daemon.Watchable

private[mill] class BspEvaluators(
    workspaceDir: os.Path,
    val evaluators: Seq[EvaluatorApi],
    debug: (() => String) => Unit,
    val watched: Seq[Watchable]
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

    val regularModules = modules
      .flatMap { case (rootModule, modules, eval) =>
        modules.collect {
          case m: BspModuleApi =>
            val uri = Utils.sanitizeUri(
              (os.Path(rootModule.moduleDirJava) / m.moduleSegments.parts).toNIO
            )

            (new BuildTargetIdentifier(uri), (m, eval))
        }
      }

    // Add script modules
    val scriptModules = evaluators.headOption.map { eval =>
      val outDir = os.Path(eval.outPathJava)
      discoverAndInstantiateScriptModules(workspaceDir, outDir, eval)
    }.getOrElse(Seq.empty)

    regularModules ++ scriptModules
  }

  private def discoverAndInstantiateScriptModules(
      workspaceDir: os.Path,
      outDir: os.Path,
      eval: EvaluatorApi
  ): Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] = {
    // Reflectively load and call `ScriptModuleInit.discoverAndInstantiateScriptModules`
    // from the evaluator's classloader
    val scriptModuleInitClass = eval
      .rootModule
      .getClass
      .getClassLoader
      .loadClass("mill.script.ScriptModuleInit")

    val result = scriptModuleInitClass
      .getMethod("discoverAndInstantiateScriptModules")
      .invoke(null, workspaceDir, outDir)
      .asInstanceOf[Seq[(java.nio.file.Path, mill.api.Result[mill.api.ExternalModule])]]

    result.map {
      case (scriptPath: java.nio.file.Path, mill.api.Result.Success(module: BspModuleApi)) =>
        (new BuildTargetIdentifier(Utils.sanitizeUri(scriptPath)), (module, eval))
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
    val syntheticIds = syntheticRootBspBuildTarget.map(_.id).toSet
    input.asScala.filterNot(syntheticIds.contains).toList.asJava
  }
}
