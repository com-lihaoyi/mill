package mill.script
import mill.api.{ExternalModule, Result}

// Cache instantiated script modules on a per-evaluation basis. This allows us to ensure
// we don't duplicate script modules when e.g. multiple downstream modules refer to the
// same upstream module. But we cannot cache them for longer because between evaluations
// the `headerData` might change requiring some modules to be re-instantiated, and it is
// hard to do that on a per-module basis so we just re-instantiate all modules every time
class ScriptModuleInit
    extends ((String, String => Option[mill.Module]) => Seq[Result[mill.api.ExternalModule]]) {

  val scriptModuleCache: collection.mutable.Map[os.Path, ScriptModule] =
    collection.mutable.Map.empty

  def moduleFor(
      scriptFile: os.Path,
      extendsConfigStrings: Option[String],
      moduleDepsStrings: Seq[String],
      compileModuleDepsStrings: Seq[String],
      runModuleDepsStrings: Seq[String],
      resolveModuleDep: String => Option[mill.Module],
      headerData: mill.api.ModuleCtx.HeaderData
  ): mill.api.Result[mill.api.ExternalModule] = {
    def relativize(s: String) = {
      if (s.startsWith("."))
        (scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot) / os.up / os.RelPath(s)).toString
      else s
    }

    def resolveOrErr(s: String) = resolveModuleDep(relativize(s)).toRight(s)
    val (moduleDepsErrors, moduleDeps) = moduleDepsStrings.partitionMap(resolveOrErr)
    val (compileModuleDepsErrors, compileModuleDeps) =
      compileModuleDepsStrings.partitionMap(resolveOrErr)
    val (runModuleDepsErrors, runModuleDeps) = runModuleDepsStrings.partitionMap(resolveOrErr)
    val allErrors = moduleDepsErrors ++ compileModuleDepsErrors ++ runModuleDepsErrors
    if (allErrors.nonEmpty) {
      mill.api.Result.Failure(
        "Unable to resolve modules: " + allErrors.map(pprint.Util.literalize(_)).mkString(", ")
      )
    } else instantiate(
      scriptFile,
      extendsConfigStrings.getOrElse {
        scriptFile.ext match {
          case "java" => "mill.script.JavaModule"
          case "kt" => "mill.script.KotlinModule"
          case "scala" => "mill.script.ScalaModule"
        }
      },
      ScriptModule.Config(scriptFile, moduleDeps, compileModuleDeps, runModuleDeps, headerData)
    )
  }

  def instantiate(
      scriptFile: os.Path,
      className: String,
      args: AnyRef*
  ): mill.api.Result[ExternalModule] = {
    val clsOrErr =
      try Result.Success(Class.forName(className))
      catch {
        case _: Throwable =>
          // Hack to try and pick up classes nested within package objects
          try Result.Success(Class.forName(className.reverse.replaceFirst("\\.", "\\$").reverse))
          catch {
            case _: java.lang.ClassNotFoundException =>
              val relPath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)
              Result.Failure(
                s"Script $relPath extends invalid class ${pprint.Util.literalize(className)}"
              )
          }
      }

    clsOrErr.flatMap(cls =>
      mill.api.ExecResult.catchWrapException {
        scriptModuleCache.getOrElseUpdate(
          scriptFile,
          cls.getDeclaredConstructors.head.newInstance(args*).asInstanceOf[ScriptModule]
        )
      }
    )
  }

  /**
   * Resolves a single script file to a module instance.
   * Exposed for use in BSP integration.
   */
  def resolveScriptModule(
      scriptFile0: String,
      resolveModuleDep: String => Option[mill.Module]
  ): Option[Result[ExternalModule]] = {
    val scriptFile = os.Path(scriptFile0, mill.api.BuildCtx.workspaceRoot)
    // Add a synthetic watch on `scriptFile`, representing the special handling
    // of `staticBuildOverrides` which is read from the script file build header
    mill.api.BuildCtx.evalWatch(scriptFile)

    Option.when(os.isFile(scriptFile)) {
      mill.internal.Util.parseHeaderData(scriptFile).flatMap(parsedHeaderData =>
        moduleFor(
          scriptFile,
          parsedHeaderData.`extends`.headOption,
          parsedHeaderData.moduleDeps,
          parsedHeaderData.compileModuleDeps,
          parsedHeaderData.runModuleDeps,
          resolveModuleDep,
          parsedHeaderData
        )
      )
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
      os.Path(mill.constants.OutFiles.out, workspaceRoot),
      skipPath
    )
      .flatMap { scriptPath =>
        resolveScriptModule(scriptPath.toString, eval.resolveScriptModuleDep).map { result =>
          (scriptPath.toNIO, result)
        }
      }
  }

  private val scriptExtensions = Set("scala", "java", "kt")

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
        scriptExtensions.contains(path.ext) // Check if it's a file with the right extension
      }
  }

  /**
   * Checks if a file starts with a `//|` build header comment.
   */
  def apply(
      scriptFileString: String,
      resolveModuleDep: String => Option[mill.Module]
  ) = {
    mill.api.BuildCtx.withFilesystemCheckerDisabled {
      resolveScriptModule(scriptFileString, resolveModuleDep).toSeq
    }
  }
}
