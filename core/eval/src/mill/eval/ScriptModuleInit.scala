package mill.eval

import mill.api.daemon.SelectMode
import mill.api.internal.Located
import mill.api.{Evaluator, ExternalModule, PrecompiledModule, Result, ScriptModule}
import mill.resolve.Resolve
import scala.annotation.unused

// Cache instantiated script modules on a per-evaluation basis. This allows us to ensure
// we don't duplicate script modules when e.g. multiple downstream modules refer to the
// same upstream module. But we cannot cache them for longer because between evaluations
// the `headerData` might change requiring some modules to be re-instantiated, and it is
// hard to do that on a per-module basis so we just re-instantiate all modules every time
class ScriptModuleInit extends ((String, Evaluator) => Seq[Result[ExternalModule]]) {

  val scriptModuleCache: collection.mutable.Map[os.Path, ExternalModule] =
    collection.mutable.Map.empty

  // Clear global caches so that stale instances from previous evaluation cycles
  // are not reused when YAML configs change between evaluations.
  mill.api.internal.PrecompiledModuleRef.cache.clear()
  mill.api.internal.PrecompiledModuleRef.headerDataParser = mill.internal.Util.parseHeaderData
  mill.internal.Util.clearPrecompiledYamlModuleCache()

  // Track the current resolution chain to detect recursive moduleDeps
  val resolvingScripts: collection.mutable.LinkedHashSet[os.Path] =
    collection.mutable.LinkedHashSet.empty

  def moduleFor(
      scriptFile: os.Path,
      extendsConfigStrings: Option[Located[String]],
      eval: Evaluator,
      headerData: mill.api.internal.HeaderData
  ): Result[ExternalModule] = {
    val scriptText = os.read(scriptFile)

    def relativize(s: String) = s match {
      case s"//$rest" => rest
      case _ if scriptFile.ext == "yaml" =>
        // YAML module dep references are module segment paths (e.g., "foo", "foo.test"),
        // not file paths, so they should not be relativized to the script file's directory
        s
      case _ =>
        val scriptFolder = scriptFile / os.up
        (scriptFolder.relativeTo(mill.api.BuildCtx.workspaceRoot) / os.RelPath(s)).toString
    }

    def resolveOrErr(located: Located[String]) =
      resolveModuleDep(eval, relativize(located.value)) match {
        case Result.Success(Some(r)) => Right(r)
        case Result.Success(None) => Left((located, None))
        case f: Result.Failure => Left((located, Some(f)))
      }

    // Collect all module deps from all levels (root + nested objects)
    val allLevelDeps = mill.api.internal.HeaderData.collectAllNestedDeps(scriptFile, headerData, "")

    // Resolve all deps and collect errors
    val allErrors = collection.mutable.Buffer.empty[(Located[String], Option[Result.Failure])]
    val moduleDepsMap = collection.mutable.Map.empty[String, Seq[mill.api.Module]]
    val compileModuleDepsMap = collection.mutable.Map.empty[String, Seq[mill.api.Module]]
    val runModuleDepsMap = collection.mutable.Map.empty[String, Seq[mill.api.Module]]
    val bomModuleDepsMap = collection.mutable.Map.empty[String, Seq[mill.api.Module]]

    for (entry <- allLevelDeps) {
      def resolve(
          deps: Seq[Located[String]],
          target: collection.mutable.Map[String, Seq[mill.api.Module]]
      ): Unit = {
        val (errors, resolved) = deps.partitionMap(resolveOrErr)
        allErrors ++= errors
        if (resolved.nonEmpty) target(entry.key) = resolved
      }
      resolve(entry.moduleDeps, moduleDepsMap)
      resolve(entry.compileModuleDeps, compileModuleDepsMap)
      resolve(entry.runModuleDeps, runModuleDepsMap)
      resolve(entry.bomModuleDeps, bomModuleDepsMap)
    }

    if (allErrors.nonEmpty) {
      val failures = allErrors.map {
        // if an upstream error is detected, just propagate it directly. Trying to decorate
        // the error to include the intermediate resolved scripts causes it to be really verbose
        case (located, Some(f)) => f.copy(path = scriptFile.toNIO, index = located.index)
        case (located, None) =>
          Result.Failure(
            s"Unable to resolve module ${pprint.Util.literalize(located.value)}",
            path = scriptFile.toNIO,
            index = located.index
          )
      }.toSeq
      Result.Failure.join(failures)
    } else {
      val scriptCls = extendsConfigStrings.map(cls => Result.Success(cls.value)).getOrElse {
        scriptFile.ext match {
          case "java" => Result.Success("mill.script.JavaModule")
          case "kt" => Result.Success("mill.script.KotlinModule")
          case "scala" => Result.Success("mill.script.ScalaModule")
          case "groovy" => Result.Success("mill.script.GroovyModule")
          case _ =>
            Result.Failure(
              s"Script ${scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)} has no `extends` clause configured and is of an unknown extension `${scriptFile.ext}`"
            )
        }
      }
      scriptCls.flatMap(
        instantiate(
          scriptFile,
          _,
          extendsConfigStrings.map(_.index),
          scriptText,
          ScriptModule.Config(
            scriptFile,
            moduleDepsMap.toMap,
            compileModuleDepsMap.toMap,
            runModuleDepsMap.toMap,
            bomModuleDepsMap.toMap,
            headerData
          )
        )
      ).flatMap { module =>
        mill.api.internal.PrecompiledModuleRef
          .findNestedConfigMismatch(module, scriptFile, headerData) match {
          case Some(f) => f
          case None => Result.Success(module)
        }
      }
    }
  }

  // Nested config key validation is shared with PrecompiledModuleRef —
  // see PrecompiledModuleRef.findNestedConfigMismatch

  def resolveModuleDep(eval: Evaluator, s: String): Result[Option[mill.Module]] = {
    eval.resolveModulesOrTasks(Seq(s), SelectMode.Multi).map(_.collectFirst { case Left(m) => m })
  }

  def instantiate(
      scriptFile: os.Path,
      className: String,
      extendsIndex: Option[Int],
      @unused scriptText: String,
      args: AnyRef*
  ): Result[ExternalModule] = {
    val clsOrErr =
      try Result.Success(Class.forName(className))
      catch {
        case _: Throwable =>
          // Hack to try and pick up classes nested within package objects
          try Result.Success(Class.forName(className.reverse.replaceFirst("\\.", "\\$").reverse))
          catch {
            case _: java.lang.ClassNotFoundException =>
              val relPath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)
              extendsIndex match {
                case Some(idx) =>
                  Result.Failure(
                    s"Script extends invalid class ${pprint.Util.literalize(className)}",
                    path = scriptFile.toNIO,
                    index = idx
                  )
                case None =>
                  Result.Failure(
                    s"$relPath:1 Script extends invalid class ${pprint.Util.literalize(className)}"
                  )
              }
          }
      }

    clsOrErr.flatMap(cls =>
      mill.api.ExecResult.catchWrapException {
        scriptModuleCache.getOrElseUpdate(
          scriptFile,
          // Check PrecompiledModuleRef's cache first to reuse instances created
          // by child aliases in the parent build module, avoiding duplicate instances
          mill.api.internal.PrecompiledModuleRef.cacheGet(scriptFile) match {
            case Some(m) => m.asInstanceOf[PrecompiledModule]
            case None =>
              val relPath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot).toString
              val extendsIdx = extendsIndex.getOrElse(0)
              val validCtor = mill.api.internal.PrecompiledModuleRef
                .validatePrecompiledClass(cls, relPath, scriptFile, extendsIdx)
              validCtor.newInstance(args*).asInstanceOf[PrecompiledModule]
          }
        )
      }
    )
  }

  /**
   * Resolves a single script file to a module instance.
   * Exposed for use in BSP integration.
   */
  def resolveScriptModule(scriptFile0: String, eval: Evaluator): Option[Result[ExternalModule]] = {
    val scriptFile = os.Path(scriptFile0, mill.api.BuildCtx.workspaceRoot)
    // Add a synthetic watch on `scriptFile`, representing the special handling
    // of `staticBuildOverrides` which is read from the script file build header
    mill.api.BuildCtx.evalWatch(scriptFile)

    Option.when(os.isFile(scriptFile)) {
      // Check for recursive moduleDeps cycle
      if (resolvingScripts.contains(scriptFile)) {
        val relPath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)
        val chain = resolvingScripts.toSeq.map(_.relativeTo(mill.api.BuildCtx.workspaceRoot))
        val cyclePath = (chain :+ relPath).mkString(" -> ")
        Result.Failure(s"Recursive moduleDeps detected: $cyclePath")
      } else {
        resolvingScripts.add(scriptFile)
        try {
          mill.internal.Util.parseHeaderData(scriptFile).flatMap(parsedHeaderData =>
            moduleFor(
              scriptFile,
              parsedHeaderData.`extends`.value.value.headOption,
              eval,
              parsedHeaderData
            )
          )
        } finally {
          resolvingScripts.remove(scriptFile)
        }
      }
    }
  }

  /**
   * Discovers and instantiates script modules for BSP integration.
   * This method must be called reflectively from the evaluator's classloader.
   */
  def discoverAndInstantiateScriptModules(
      eval: mill.api.Evaluator,
      skipPath: (String, Boolean) => Boolean
  )
      : Seq[(java.nio.file.Path, Result[ExternalModule])] = {
    // For now, we don't resolve moduleDeps as that would require access to other modules
    import mill.api.BuildCtx.workspaceRoot
    discoverScriptFiles(
      workspaceRoot,
      os.Path(mill.constants.OutFiles.OutFiles.out, workspaceRoot),
      skipPath
    )
      .flatMap { scriptPath =>
        resolveScriptModule(scriptPath.toString, eval).map { result =>
          (scriptPath.toNIO, result)
        }
      }
  }

  private val scriptExtensions = Set("scala", "java", "kt", "yaml")

  /**
   * Discovers all script files in the given workspace directory.
   *
   * @param workspaceDir The root workspace directory to search
   * @param outDir The output directory to exclude (typically `workspaceDir / "out"`)
   * @param skipPath Function to determine if a path should be skipped (receives relative path and isDirectory flag)
   * @return A sequence of paths to script files
   */
  def discoverScriptFiles(
      workspaceDir: os.Path,
      outDir: os.Path,
      skipPath: (String, Boolean) => Boolean
  ): Seq[os.Path] = {
    os.walk(
      workspaceDir,
      skip = { path =>
        if (path.startsWith(outDir)) {
          true
        } else {
          val relativePath = path.relativeTo(workspaceDir).toString
          val isDirectory = os.isDir(path)
          skipPath(relativePath, isDirectory)
        }
      }
    )
      .filter { path =>
        os.isFile(path) &&
        scriptExtensions.contains(path.ext) &&
        // For .yaml files, only discover *.mill.yaml pre-compiled modules
        (path.ext != "yaml" || (path.last.endsWith(
          ".mill.yaml"
        ) && mill.internal.Util.isPrecompiledYamlModule(path)))
      }
  }

  /**
   * Resolves a pre-compiled module from a directory path. Checks for
   * `package.mill.yaml` or `build.mill.yaml` in the directory with
   * `mill-experimental-precompiled-module: true` in their header.
   */
  def resolvePrecompiledModule(
      dirPath: String,
      eval: Evaluator
  ): Option[Result[ExternalModule]] = {
    val dir = os.Path(dirPath, mill.api.BuildCtx.workspaceRoot)
    if (!os.isDir(dir)) None
    else {
      val candidates = Seq("package.mill.yaml", "build.mill.yaml")
      candidates.iterator.flatMap { name =>
        val yamlFile = dir / name
        if (os.isFile(yamlFile) && mill.internal.Util.isPrecompiledYamlModule(yamlFile))
          resolveScriptModule(yamlFile.toString, eval)
        else None
      }.nextOption()
    }
  }

  private def discoverPrecompiledYamlModules(all: Boolean): Seq[os.Path] = {
    val workspaceDir = mill.api.BuildCtx.workspaceRoot
    mill.internal.BuildFileDiscovery
      .walkNestedBuildFiles(
        workspaceDir,
        os.Path(mill.constants.OutFiles.OutFiles.out, workspaceDir)
      )
      .filter(path => path.last.endsWith(".mill.yaml"))
      .filter(mill.internal.Util.isPrecompiledYamlModule)
      .filter { path =>
        val rel = path.relativeTo(workspaceDir)
        all || (path.last == "package.mill.yaml" && rel.segments.length == 2)
      }
      .sortBy(_.toString)
  }

  /**
   * Entry point for the script module resolver. First tries file-based resolution
   * (for script modules), then tries directory-based resolution (for pre-compiled modules).
   */
  def apply(scriptFileString: String, eval: Evaluator) = {
    mill.api.BuildCtx.withFilesystemCheckerDisabled {
      scriptFileString match {
        case Resolve.ScriptModuleQuery.AllPrecompiledModules =>
          discoverPrecompiledYamlModules(all = true)
            .flatMap(path => resolveScriptModule(path.toString, eval))
        case Resolve.ScriptModuleQuery.DirectPrecompiledModules =>
          discoverPrecompiledYamlModules(all = false)
            .flatMap(path => resolveScriptModule(path.toString, eval))
        case _ =>
          resolveScriptModule(scriptFileString, eval).toSeq match {
            case resolved if resolved.nonEmpty => resolved
            case _ =>
              // Try resolving as a directory path for pre-compiled modules
              resolvePrecompiledModule(scriptFileString, eval).toSeq
          }
      }
    }
  }
}
