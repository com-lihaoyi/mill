package mill.eval

import mill.api.daemon.SelectMode
import mill.api.internal.Located
import mill.api.{Evaluator, ExternalModule, Result, ScriptModule}
import scala.annotation.unused

// Cache instantiated script modules on a per-evaluation basis. This allows us to ensure
// we don't duplicate script modules when e.g. multiple downstream modules refer to the
// same upstream module. But we cannot cache them for longer because between evaluations
// the `headerData` might change requiring some modules to be re-instantiated, and it is
// hard to do that on a per-module basis so we just re-instantiate all modules every time
class ScriptModuleInit extends ((String, Evaluator) => Seq[Result[ExternalModule]]) {

  val scriptModuleCache: collection.mutable.Map[os.Path, ExternalModule] =
    collection.mutable.Map.empty

  // Track the current resolution chain to detect recursive moduleDeps
  val resolvingScripts: collection.mutable.LinkedHashSet[os.Path] =
    collection.mutable.LinkedHashSet.empty

  def moduleFor(
      scriptFile: os.Path,
      extendsConfigStrings: Option[Located[String]],
      moduleDepsStrings: Seq[Located[String]],
      compileModuleDepsStrings: Seq[Located[String]],
      runModuleDepsStrings: Seq[Located[String]],
      eval: Evaluator,
      headerData: mill.api.internal.HeaderData
  ): Result[ExternalModule] = {
    val scriptText = os.read(scriptFile)

    def relativize(s: String) = s match {
      case s"//$rest" => rest
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

    val (moduleDepsErrors, moduleDeps) = moduleDepsStrings.partitionMap(resolveOrErr)
    val (compileModuleDepsErrors, compileModuleDeps) =
      compileModuleDepsStrings.partitionMap(resolveOrErr)
    val (runModuleDepsErrors, runModuleDeps) = runModuleDepsStrings.partitionMap(resolveOrErr)

    val allErrors = moduleDepsErrors ++ compileModuleDepsErrors ++ runModuleDepsErrors

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
      }
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
          ScriptModule.Config(scriptFile, moduleDeps, compileModuleDeps, runModuleDeps, headerData)
        )
      )
    }
  }

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
              parsedHeaderData.moduleDeps.value.value,
              parsedHeaderData.compileModuleDeps.value.value,
              parsedHeaderData.runModuleDeps.value.value,
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
