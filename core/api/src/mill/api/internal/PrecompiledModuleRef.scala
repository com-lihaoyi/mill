package mill.api.internal

/**
 * Instantiates a precompiled module from a YAML config file at module construction time,
 * allowing it to be discovered by `resolve _` as a child of its parent build module.
 *
 * Called from generated build code. The extends class name, YAML path, and module deps
 * are embedded at code-generation time.
 *
 * Instances are cached by script file path so that both the child alias path (via CodeGen)
 * and the script module resolver path (via ScriptModuleInit) return the same instance.
 */
object PrecompiledModuleRef {

  /**
   * Cache for precompiled module instances, shared between CodeGen-generated aliases
   * (via [[apply]]) and script module resolution (via ScriptModuleInit).
   *
   * This is a global mutable cache because generated build code calls [[apply]] directly
   * without access to the ScriptModuleInit instance. It must be cleared at the start of
   * each evaluation cycle to avoid stale instances when YAML configs change; this is done
   * by ScriptModuleInit's constructor.
   */
  private[mill] val cache: collection.mutable.Map[os.Path, mill.api.Module] =
    collection.mutable.Map.empty

  /**
   * Resolve a module reference like "foo" or "foo.test" by using reflection
   * to access specific named methods on the parent, avoiding triggering all
   * lazy vals (which would cause circular initialization deadlocks).
   */
  def resolveModuleRef(parent: mill.api.Module, ref: String): mill.api.Module = {
    val segments = ref.split("\\.")
    var current: Any = parent
    for (seg <- segments) {
      val cls = current.getClass
      val method =
        try cls.getMethod(seg)
        catch {
          case _: NoSuchMethodException =>
            throw new IllegalStateException(
              s"Cannot resolve module dependency '$ref' (no method '$seg' on ${cls.getName})"
            )
        }
      current = method.invoke(current)
    }
    current.asInstanceOf[mill.api.Module]
  }

  /**
   * Validate that all nested config dep keys (e.g. "test") correspond to actual
   * nested objects in the module class. This catches typos like `object typo:` in YAML
   * that would otherwise silently have their moduleDeps ignored.
   *
   * Called after instantiation rather than in the constructor so that errors don't
   * poison lazy val initialization and cause deadlocks.
   */
  private def validateNestedConfigKeys(
      module: mill.api.Module,
      scriptFile: os.Path,
      moduleDeps: Map[String, Seq[mill.api.Module]],
      compileModuleDeps: Map[String, Seq[mill.api.Module]],
      runModuleDeps: Map[String, Seq[mill.api.Module]],
      bomModuleDeps: Map[String, Seq[mill.api.Module]]
  ): Unit = {
    val allConfigKeys = (
      moduleDeps.keySet ++
        compileModuleDeps.keySet ++
        runModuleDeps.keySet ++
        bomModuleDeps.keySet
    ).filter(_.nonEmpty) // skip root key ""

    for (key <- allConfigKeys) {
      // Only validate the first segment (direct child); nested paths like "test.sub"
      // will be validated when the child module does its own check
      val directChild = key.split("\\.").head
      val hasMethod =
        try { module.getClass.getMethod(directChild); true }
        catch { case _: NoSuchMethodException => false }
      if (!hasMethod) {
        throw new mill.api.daemon.Result.Exception(
          s"Config key ${pprint.Util.literalize("object " + directChild)} in " +
            s"${scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)} " +
            s"does not match any nested module in ${module.getClass.getName}",
          Some(mill.api.daemon.Result.Failure(
            s"Config key ${pprint.Util.literalize("object " + directChild)} " +
              s"does not match any nested module in ${module.getClass.getName}",
            scriptFile.toNIO,
            0
          ))
        )
      }
    }
  }

  def apply(
      parent: mill.api.Module,
      relPath: String,
      extendsClass: String,
      moduleDeps: Map[String, Seq[mill.api.Module]],
      compileModuleDeps: Map[String, Seq[mill.api.Module]],
      runModuleDeps: Map[String, Seq[mill.api.Module]],
      bomModuleDeps: Map[String, Seq[mill.api.Module]]
  ): mill.api.Module = {
    val scriptFile = os.Path(relPath, mill.api.BuildCtx.workspaceRoot)
    cache.getOrElseUpdate(scriptFile, {
      val headerData = HeaderData.parseHeaderData(scriptFile) match {
        case mill.api.Result.Success(hd) => hd
        case _ => HeaderData(rest = Map.empty)
      }
      val config = mill.api.ScriptModule.Config(
        scriptFile = scriptFile,
        moduleDeps = moduleDeps,
        compileModuleDeps = compileModuleDeps,
        runModuleDeps = runModuleDeps,
        bomModuleDeps = bomModuleDeps,
        headerData = headerData
      )
      val cls =
        try Class.forName(extendsClass)
        catch {
          case _: ClassNotFoundException =>
            try Class.forName(extendsClass.reverse.replaceFirst("\\.", "\\$").reverse)
            catch {
              case e: ClassNotFoundException =>
                throw new IllegalStateException(
                  s"Precompiled module $relPath extends class $extendsClass which cannot be found",
                  e
                )
            }
        }
      val module = cls.getDeclaredConstructors.head.newInstance(config).asInstanceOf[mill.api.Module]
      validateNestedConfigKeys(
        module,
        scriptFile,
        moduleDeps,
        compileModuleDeps,
        runModuleDeps,
        bomModuleDeps
      )
      module
    })
  }
}
