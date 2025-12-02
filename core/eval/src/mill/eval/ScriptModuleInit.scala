package mill.eval

import mill.api.daemon.SelectMode
import mill.api.internal.Located
import mill.api.{Evaluator, ExternalModule, Result, ScriptModule}

// Cache instantiated script modules on a per-evaluation basis. This allows us to ensure
// we don't duplicate script modules when e.g. multiple downstream modules refer to the
// same upstream module. But we cannot cache them for longer because between evaluations
// the `headerData` might change requiring some modules to be re-instantiated, and it is
// hard to do that on a per-module basis so we just re-instantiate all modules every time
class ScriptModuleInit extends ((String, Evaluator) => Seq[Result[ExternalModule]]) {

  val scriptModuleCache: collection.mutable.Map[os.Path, ExternalModule] =
    collection.mutable.Map.empty

  def moduleFor(
      scriptFile: os.Path,
      extendsConfigStrings: Option[Located[String]],
      moduleDepsStrings: Seq[Located[String]],
      compileModuleDepsStrings: Seq[Located[String]],
      runModuleDepsStrings: Seq[Located[String]],
      eval: Evaluator,
      headerData: mill.api.ModuleCtx.HeaderData
  ): Result[ExternalModule] = {
    val scriptText = os.read(scriptFile)

    def relativize(s: String) = {
      if (s.startsWith("."))
        (scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot) / os.up / os.RelPath(s)).toString
      else s
    }

    def resolveOrErr(located: Located[String]) =
      resolveModuleDep(eval, relativize(located.value)).toRight(located)
    val (moduleDepsErrors, moduleDeps) = moduleDepsStrings.partitionMap(resolveOrErr)
    val (compileModuleDepsErrors, compileModuleDeps) =
      compileModuleDepsStrings.partitionMap(resolveOrErr)
    val (runModuleDepsErrors, runModuleDeps) = runModuleDepsStrings.partitionMap(resolveOrErr)
    val allErrors = moduleDepsErrors ++ compileModuleDepsErrors ++ runModuleDepsErrors
    if (allErrors.nonEmpty) {
      val failures = allErrors.map { located =>
        Result.Failure(
          s"Unable to resolve module ${pprint.Util.literalize(located.value)}",
          path = scriptFile.toNIO,
          index = located.index
        )
      }
      Result.Failure.join(failures)
    } else instantiate(
      scriptFile,
      extendsConfigStrings.map(_.value).getOrElse {
        scriptFile.ext match {
          case "java" => "mill.script.JavaModule"
          case "kt" => "mill.script.KotlinModule"
          case "scala" => "mill.script.ScalaModule"
        }
      },
      extendsConfigStrings.map(_.index),
      scriptText,
      ScriptModule.Config(scriptFile, moduleDeps, compileModuleDeps, runModuleDeps, headerData)
    )
  }

  def resolveModuleDep(eval: Evaluator, s: String): Option[mill.Module] = {
    eval.resolveModulesOrTasks(Seq(s), SelectMode.Multi)
      .toOption
      .toSeq
      .flatten
      .collectFirst { case Left(m) => m }
  }

  def instantiate(
      scriptFile: os.Path,
      className: String,
      extendsIndex: Option[Int],
      scriptText: String,
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
          cls.getDeclaredConstructors.head.newInstance(args*).asInstanceOf[ScriptModule]
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
      mill.internal.Util.parseHeaderData(scriptFile).flatMap(parsedHeaderData =>
        moduleFor(
          scriptFile,
          parsedHeaderData.`extends`.value.headOption,
          parsedHeaderData.moduleDeps.value,
          parsedHeaderData.compileModuleDeps.value,
          parsedHeaderData.runModuleDeps.value,
          eval,
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
      os.Path(mill.constants.OutFiles.OutFiles.out, workspaceRoot),
      skipPath
    )
      .flatMap { scriptPath =>
        resolveScriptModule(scriptPath.toString, eval).map { result =>
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
  def apply(scriptFileString: String, eval: Evaluator) = {
    mill.api.BuildCtx.withFilesystemCheckerDisabled {
      resolveScriptModule(scriptFileString, eval).toSeq
    }
  }
}
