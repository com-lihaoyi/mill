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
import mill.api.daemon.Watchable
import org.eclipse.jgit.ignore.{FastIgnoreRule, IgnoreNode}

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

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
      .flatMap { case (sources: Seq[Path], _: Seq[Path]) =>
        sources.map(os.Path(_, workspaceDir).subRelativeTo(workspaceDir))
      }
  }
  val nonScriptResources = evaluators.flatMap { ev =>
    val bspSourceTasks: Seq[TaskApi[Seq[Path]]] =
      transitiveModules(ev.rootModule)
        .collect { case m: JavaModuleApi => m.bspJavaModule().bspBuildTargetResources }
    ev.executeApi(bspSourceTasks)
      .values
      .get
      .flatMap { case resources: Seq[Path] =>
        resources.map(os.Path(_, workspaceDir).subRelativeTo(workspaceDir))
      }
  }

  val bspScriptIgnore: Seq[String] = {
    // look for this in the first meta-build frame, which would be the meta-build configured
    // by a `//|` build header in the main `build.mill` file in the project root folder
    evaluators.lift(1).toSeq.flatMap { ev =>
      val bspScriptIgnore: Seq[TaskApi[Seq[String]]] =
        Seq(ev.rootModule).collect { case m: MillBuildRootModuleApi => m.bspScriptIgnoreAll }

      ev.executeApi(bspScriptIgnore)
        .values
        .get
        .flatMap { case sources: Seq[String] => sources }

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
