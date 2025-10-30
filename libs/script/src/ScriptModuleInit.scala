package mill.script
import mill.*
import mill.api.{ExternalModule, Result}
import mill.script.ScriptModule.parseHeaderData

object ScriptModuleInit
    extends (
        (
            String,
            String => Option[mill.Module],
            Boolean,
            Option[String]
        ) => Seq[Result[mill.api.ExternalModule]]
    ) {

  // Cache instantiated script modules on a per-classloader basis. This lets us avoid
  // instantiating the same script twice, e.g. once directly and once when resolving a
  // downstream script's `moduleDeps`. This is kept on the `ScriptModuleInit` object scoped
  // to the build classloader and is garbage collected when the classloader is discarded.
  val scriptModuleCache: collection.mutable.Map[os.Path, ExternalModule] =
    collection.mutable.Map.empty

  def moduleFor(
      scriptFile: os.Path,
      extendsConfigStrings: Option[String],
      moduleDepsStrings: Seq[String],
      compileModuleDepsStrings: Seq[String],
      runModuleDepsStrings: Seq[String],
      resolveModuleDep: String => Option[mill.Module]
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
      ScriptModule.Config(scriptFile, moduleDeps, compileModuleDeps, runModuleDeps)
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
          cls.getDeclaredConstructors.head.newInstance(args*).asInstanceOf[ExternalModule]
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
    Option.when(os.isFile(scriptFile)) {
      parseHeaderData(scriptFile).flatMap(parsedHeaderData =>
        moduleFor(
          scriptFile,
          parsedHeaderData.`extends`.headOption,
          parsedHeaderData.moduleDeps,
          parsedHeaderData.compileModuleDeps,
          parsedHeaderData.runModuleDeps,
          resolveModuleDep
        )
      )
    }
  }

  /**
   * Discovers and instantiates script modules for BSP integration.
   * This method must be called reflectively from the evaluator's classloader.
   */
  def discoverAndInstantiateScriptModules(
      nonScriptSourceFolders0: Seq[java.nio.file.Path],
      eval: mill.api.Evaluator
  )
      : Seq[(java.nio.file.Path, Result[ExternalModule])] = {
    // For now, we don't resolve moduleDeps as that would require access to other modules
    import mill.api.BuildCtx.workspaceRoot
    val nonScriptSourceFolders = nonScriptSourceFolders0.map(os.Path(_))
    discoverScriptFiles(workspaceRoot, os.Path(mill.constants.OutFiles.out, workspaceRoot))
      .filter(p => !nonScriptSourceFolders.exists(p.startsWith(_)))
      .flatMap { scriptPath =>
        try {
          resolveScriptModule(scriptPath.toString, eval.resolveScriptModuleDep).map { result =>
            (scriptPath.toNIO, result)
          }
        } catch { case e: Exception =>
          // If resolving a script module fails, just log it and ignore
          // it rather than blowing up the whole BSP import process
          println("Failed to instantiate script during BSP import and discovery: " + scriptPath)
          println(e)
          e.printStackTrace()
          None
        }
      }
  }

  private val scriptExtensions = Set("scala", "java", "kt")

  /**
   * Discovers all script files in the given workspace directory.
   *
   * @param workspaceDir The root workspace directory to search
   * @param outDir The output directory to exclude (typically `workspaceDir / "out"`)
   * @return A sequence of paths to script files
   */
  def discoverScriptFiles(workspaceDir: os.Path, outDir: os.Path): Seq[os.Path] = {
    os.walk(workspaceDir)
      .filter { path =>
        os.isFile(path) &&
        scriptExtensions.contains(path.ext) && // Check if it's a file with the right extension
        !path.startsWith(outDir) // Exclude files in the out/ directory
      }
  }

  /**
   * Checks if a file starts with a `//|` build header comment.
   */

  def apply(
      scriptFileString: String,
      resolveModuleDep: String => Option[mill.Module],
      resolveChildren: Boolean,
      nameOpt: Option[String]
  ) = {
    val workspace = mill.api.BuildCtx.workspaceRoot

    mill.api.BuildCtx.withFilesystemCheckerDisabled {
      val scriptFile0 = os.Path(scriptFileString, workspace)
      if (resolveChildren) {
        nameOpt match {
          case Some(n) => resolveScriptModule((scriptFile0 / n).toString, resolveModuleDep).toSeq
          case None =>
            if (!os.isDir(scriptFile0)) Nil
            else os.list(scriptFile0).filter(os.isDir).flatMap(p =>
              resolveScriptModule(p.toString, resolveModuleDep)
            )
        }
      } else resolveScriptModule(scriptFileString, resolveModuleDep).toSeq
    }
  }
}
