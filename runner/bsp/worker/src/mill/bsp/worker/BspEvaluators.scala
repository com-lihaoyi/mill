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
    import mill.script.ScriptFileDiscovery

    // Get the evaluator's classloader to ensure script modules are loaded in the same context
    val evalClassLoader = eval.rootModule.getClass.getClassLoader

    // For now, we don't resolve moduleDeps as that would require access to other modules
    val resolveModuleDep: String => Option[mill.Module] = _ => None

    ScriptFileDiscovery
      .discoverScriptFiles(workspaceDir, outDir)
      .flatMap { scriptPath =>
        // Reflectively load `ScriptModuleInit` from the evaluator's classloader
        // and invoke `resolveScriptModule`
        val scriptModuleInitClass = evalClassLoader.loadClass("mill.script.ScriptModuleInit$")
        val scriptModuleInit = scriptModuleInitClass.getField("MODULE$").get(null)
        val resolveScriptModuleMethod = scriptModuleInitClass.getMethods
          .find(m => m.getName == "resolveScriptModule")
          .getOrElse(throw new NoSuchMethodException("resolveScriptModule not found"))

        val result = resolveScriptModuleMethod
          .invoke(scriptModuleInit, scriptPath.toString, resolveModuleDep)
          .asInstanceOf[Option[mill.api.Result[Any]]]

        result.map {
          case mill.api.Result.Success(module: BspModuleApi) =>
            val uri = Utils.sanitizeUri(scriptPath.toNIO)
            val id = new BuildTargetIdentifier(uri)
            (id, (module, eval))
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
    val syntheticIds = syntheticRootBspBuildTarget.map(_.id).toSet
    input.asScala.filterNot(syntheticIds.contains).toList.asJava
  }
}
