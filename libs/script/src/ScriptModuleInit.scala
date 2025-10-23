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
      millFile: os.Path,
      extendsConfig: Option[String],
      moduleDeps: Seq[String],
      compileModuleDeps: Seq[String],
      runModuleDeps: Seq[String],
      resolveModuleDep: String => Option[mill.Module]
  ) = {
    scriptModuleCache.synchronized {
      scriptModuleCache.getOrElseUpdate(
        millFile,
        instantiate(
          extendsConfig.getOrElse {
            millFile.ext match {
              case "java" => "mill.script.JavaModule"
              case "kt" => "mill.script.KotlinModule"
              case "scala" => "mill.script.ScalaModule"
              case "sc" => "mill.script.ScalaScriptModule"
            }
          },
          ScriptModule.Config(
            millFile,
            moduleDeps.flatMap(resolveModuleDep(_)),
            compileModuleDeps.flatMap(resolveModuleDep(_)),
            runModuleDeps.flatMap(resolveModuleDep(_))
          )
        )
      )
    }
  }

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
  def discoverAndInstantiateScriptModules(nonScriptSourceFolders0: Seq[java.nio.file.Path])
      : Seq[(java.nio.file.Path, Result[ExternalModule])] = {
    // For now, we don't resolve moduleDeps as that would require access to other modules
    val resolveModuleDep: String => Option[mill.Module] = _ => None
    import mill.api.BuildCtx.workspaceRoot
    val nonScriptSourceFolders = nonScriptSourceFolders0.map(os.Path(_))
    discoverScriptFiles(workspaceRoot, os.Path(mill.constants.OutFiles.out, workspaceRoot))
      .filter(p => !nonScriptSourceFolders.exists(p.startsWith(_)))
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
        os.isFile(path) &&
        scriptExtensions.contains(path.ext) && // Check if it's a file with the right extension
        !path.startsWith(outDir) // Exclude files in the out/ directory
      }
  }

  /**
   * Checks if a file starts with a `//|` build header comment.
   */

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
