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
  def instantiate(className: String, args: AnyRef*): ExternalModule = {
    val cls =
      try Class.forName(className)
      catch {
        case _: Throwable =>
          // Hack to try and pick up classes nested within package objects
          Class.forName(className.reverse.replaceFirst("\\.", "\\$").reverse)
      }

    cls.getDeclaredConstructors.head.newInstance(args*).asInstanceOf[ExternalModule]
  }
  def moduleFor(
      millFile: os.Path,
      extendsConfig: Option[String],
      moduleDeps: Seq[String],
      compileModuleDeps: Seq[String],
      runModuleDeps: Seq[String],
      resolveModuleDep: String => Option[mill.Module]
  ) = {
    val className = extendsConfig.getOrElse {
      millFile.ext match {
        case "java" => "mill.script.ScriptModule$JavaModule"
        case "scala" => "mill.script.ScriptModule$ScalaModule"
        case "kt" => "mill.script.ScriptModule$KotlinModule"
      }
    }

    instantiate(
      className,
      ScriptModule.Config(
        millFile,
        moduleDeps.map(resolveModuleDep(_).get),
        compileModuleDeps.map(resolveModuleDep(_).get),
        runModuleDeps.map(resolveModuleDep(_).get)
      )
    )
  }

  /**
   * Resolves a single script file to a module instance.
   * Exposed for use in BSP integration.
   */
  def resolveScriptModule(
      millFile0: String,
      resolveModuleDep: String => Option[mill.Module]
  ): Option[Result[ExternalModule]] = {
    val millFile = os.Path(millFile0, mill.api.BuildCtx.workspaceRoot)
    Option.when(os.isFile(millFile)) {
      Result.create {
        val parsedHeaderData = parseHeaderData(millFile)
        moduleFor(
          millFile,
          parsedHeaderData.`extends`.headOption,
          parsedHeaderData.moduleDeps,
          parsedHeaderData.compileModuleDeps,
          parsedHeaderData.runModuleDeps,
          resolveModuleDep
        )
      }
    }
  }

  /**
   * Discovers and instantiates script modules for BSP integration.
   * This method must be called reflectively from the evaluator's classloader.
   */
  def discoverAndInstantiateScriptModules(): Seq[(java.nio.file.Path, Result[ExternalModule])] = {
    // For now, we don't resolve moduleDeps as that would require access to other modules
    val resolveModuleDep: String => Option[mill.Module] = _ => None

    discoverScriptFiles(
      mill.api.BuildCtx.workspaceRoot,
      os.Path(mill.constants.OutFiles.out, mill.api.BuildCtx.workspaceRoot)
    )
      .flatMap { scriptPath =>
        resolveScriptModule(scriptPath.toString, resolveModuleDep).map { result =>
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
   * @return A sequence of paths to script files
   */
  def discoverScriptFiles(workspaceDir: os.Path, outDir: os.Path): Seq[os.Path] = {
    os.walk(workspaceDir)
      .filter { path =>
        // Check if it's a file with the right extension
        os.isFile(path) &&
        scriptExtensions.contains(path.ext) &&
        // Exclude files in the out/ directory
        !path.startsWith(outDir) &&
        // Check if file starts with //| header
        hasScriptHeader(path)
      }
  }

  /**
   * Checks if a file starts with a `//|` build header comment.
   */
  private def hasScriptHeader(path: os.Path): Boolean = {
    try {
      val lines = os.read.lines(path)
      lines.headOption.exists(_.startsWith("//|"))
    } catch {
      case _: Exception => false
    }
  }

  def apply(
      millFileString: String,
      resolveModuleDep: String => Option[mill.Module],
      resolveChildren: Boolean,
      nameOpt: Option[String]
  ) = {
    val workspace = mill.api.BuildCtx.workspaceRoot

    mill.api.BuildCtx.withFilesystemCheckerDisabled {
      val millFile0 = os.Path(millFileString, workspace)
      if (resolveChildren) {
        nameOpt match {
          case Some(n) => resolveScriptModule((millFile0 / n).toString, resolveModuleDep).toSeq
          case None =>
            if (!os.isDir(millFile0)) Nil
            else os.list(millFile0).filter(os.isDir).flatMap(p =>
              resolveScriptModule(p.toString, resolveModuleDep)
            )
        }
      } else resolveScriptModule(millFileString, resolveModuleDep).toSeq
    }
  }
}
