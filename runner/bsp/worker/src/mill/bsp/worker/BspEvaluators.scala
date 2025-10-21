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
        try {
          // Reflectively load ScriptModuleInit from the evaluator's classloader
          val scriptModuleInitClass = evalClassLoader.loadClass("mill.script.ScriptModuleInit$")
          val scriptModuleInit = scriptModuleInitClass.getField("MODULE$").get(null)

          // Find the resolveScriptModule method by name and parameter count
          // We can't use getMethod with exact types due to generics erasure
          val resolveScriptModuleMethod = scriptModuleInitClass.getMethods
            .find(m => m.getName == "resolveScriptModule" && m.getParameterCount == 2)
            .getOrElse(throw new NoSuchMethodException("resolveScriptModule not found"))

          // Call resolveScriptModule
          val result = resolveScriptModuleMethod.invoke(
            scriptModuleInit,
            scriptPath.toString,
            resolveModuleDep
          )

          // Pattern match on the result (which is Option[Result[ExternalModule]])
          result match {
            case Some(successResult) =>
              // Check if it's a Result.Success
              val resultClass = successResult.getClass
              if (resultClass.getName.contains("Result$Success")) {
                // Extract the module from Success
                val valueMethod = resultClass.getMethod("value")
                val module = valueMethod.invoke(successResult)

                // Check if it's a BspModuleApi
                if (module.isInstanceOf[BspModuleApi]) {
                  val uri = Utils.sanitizeUri(scriptPath.toNIO)
                  val id = new BuildTargetIdentifier(uri)
                  Some((id, (module.asInstanceOf[BspModuleApi], eval)))
                } else {
                  mill.constants.DebugLog.println(s"Script module for $scriptPath is not a BspModuleApi")
                  None
                }
              } else {
                // It's a Failure
                val msgMethod = resultClass.getMethod("msg")
                val msg = msgMethod.invoke(successResult)
                mill.constants.DebugLog.println(s"Failed to instantiate script module for $scriptPath: $msg")
                None
              }
            case None =>
              None
            case _ =>
              mill.constants.DebugLog.println(s"Unexpected result type for $scriptPath")
              None
          }
        } catch {
          case e: Exception =>
            mill.constants.DebugLog.println(s"Failed to instantiate script module for $scriptPath: ${e.getMessage}")
            None
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
