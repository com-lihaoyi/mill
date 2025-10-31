package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.api.daemon.internal.bsp.BspModuleApi
import mill.api.daemon.internal.{BaseModuleApi, EvaluatorApi, JavaModuleApi, ModuleApi, TaskApi}
import mill.api.daemon.Watchable

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private[mill] class BspEvaluators(
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

  private val transitiveDependencyModules0 = new ConcurrentHashMap[ModuleApi, Seq[ModuleApi]]
  private val transitiveModulesEnableBsp0 =
    new ConcurrentHashMap[ModuleApi, Option[Seq[BspModuleApi]]]

  // Compute all transitive dependency modules via moduleDeps + compileModuleDeps
  private def transitiveDependencyModules(module: ModuleApi): Seq[ModuleApi] = {
    if (!transitiveDependencyModules0.contains(module)) {
      val directDependencies = module match {
        case jm: JavaModuleApi => jm.recursiveModuleDeps ++ jm.compileModuleDepsChecked
        case _ => Nil
      }
      val value = Seq(module) ++ directDependencies.flatMap(transitiveDependencyModules)
      transitiveDependencyModules0.putIfAbsent(module, value)
    }

    transitiveDependencyModules0.get(module)
  }

  private def transitiveModulesEnableBsp(module: ModuleApi): Option[Seq[BspModuleApi]] = {
    if (!transitiveModulesEnableBsp0.contains(module)) {
      val disabledTransitiveModules = transitiveDependencyModules(module).collect {
        case b: BspModuleApi if !b.enableBsp => b
      }
      val value =
        if (disabledTransitiveModules.isEmpty) None
        else Some(disabledTransitiveModules)
      transitiveModulesEnableBsp0.putIfAbsent(module, value)
    }

    transitiveModulesEnableBsp0.get(module)
  }

  private def moduleUri(rootModule: ModuleApi, module: ModuleApi) = Utils.sanitizeUri(
    (os.Path(rootModule.moduleDirJava) / module.moduleSegments.parts).toNIO
  )

  lazy val bspModulesIdList0: Seq[(BuildTargetIdentifier, (BspModuleApi, EvaluatorApi))] =
    for {
      eval <- evaluators
      bspModule <- transitiveModules(eval.rootModule).collect { case m: BspModuleApi => m }
      uri = moduleUri(eval.rootModule, bspModule)
      if {
        if (bspModule.enableBsp)
          transitiveModulesEnableBsp(bspModule) match {
            case Some(disabledTransitiveModules) =>
              val uris = disabledTransitiveModules.map(moduleUri(eval.rootModule, _))
              eval.baseLogger.warn(
                s"BSP disabled for target $uri because of its dependencies ${uris.mkString(", ")}"
              )
              false
            case None =>
              true
          }
        else {
          eval.baseLogger.info(s"BSP disabled for target $uri via BspModuleApi#enableBsp")
          false
        }
      }
    } yield (new BuildTargetIdentifier(uri), (bspModule, eval))

  val nonScriptSources = evaluators.flatMap { ev =>
    val bspSourceTasks: Seq[TaskApi[(sources: Seq[Path], generatedSources: Seq[Path])]] =
      transitiveModules(ev.rootModule)
        .collect { case m: JavaModuleApi => m.bspJavaModule().bspBuildTargetSources }

    ev.executeApi(bspSourceTasks)
      .values
      .get
      .flatMap { case (sources: Seq[Path], _: Seq[Path]) => sources }
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
    // Reflectively load and call `ScriptModuleInit.discoverAndInstantiateScriptModules`
    // from the evaluator's classloader
    val scriptModuleInitClass = eval
      .rootModule
      .getClass
      .getClassLoader
      .loadClass("mill.script.ScriptModuleInit")

    val result = scriptModuleInitClass
      .getMethod(
        "discoverAndInstantiateScriptModules",
        classOf[Seq[java.nio.file.Path]],
        eval.getClass
          .getClassLoader
          .loadClass("mill.api.Evaluator")
      )
      .invoke(null, nonScriptSources, eval)
      .asInstanceOf[Seq[(java.nio.file.Path, mill.api.Result[BspModuleApi])]]

    result.flatMap {
      case (scriptPath: java.nio.file.Path, mill.api.Result.Success(module: BspModuleApi)) =>
        Some((new BuildTargetIdentifier(Utils.sanitizeUri(scriptPath)), (module, eval)))
      case (scriptPath: java.nio.file.Path, mill.api.Result.Failure(msg: String)) =>
        println(s"Failed to instantiate script module for BSP: $scriptPath failed with $msg")
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
