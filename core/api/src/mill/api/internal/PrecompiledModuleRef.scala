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

  // Tracks which relPaths are currently being constructed by `apply` on the current thread.
  // Used to detect self-referential moduleDeps (e.g. module A depends on A) that
  // would otherwise deadlock due to recursive lazy val initialization.
  private[mill] val constructing: ThreadLocal[java.util.Set[String]] =
    ThreadLocal.withInitial(() => new java.util.HashSet[String]())

  /**
   * Resolve a module reference like "foo" or "foo.test" by using reflection
   * to access specific named methods on the parent, avoiding triggering all
   * lazy vals (which would cause circular initialization deadlocks).
   *
   * @param scriptFile the YAML config file declaring this dep, for error reporting
   * @param index the byte offset in the YAML file where this dep is declared
   */
  def resolveModuleRef(
      parent: mill.api.Module,
      ref: String,
      scriptFile: String,
      index: Int
  ): mill.api.Module = {
    val scriptPath = os.Path(scriptFile, mill.api.BuildCtx.workspaceRoot)
    // Check if the target module is currently being constructed on this thread.
    // The ref "foo" or "foo.bar" maps to a precompiled module at e.g. "foo/package.mill.yaml".
    // If that path is in `constructing`, invoking it would deadlock on the lazy val lock.
    val firstSegment = ref.split("\\.").head
    val set = constructing.get()
    for (name <- Seq("package.mill.yaml", "build.mill.yaml")) {
      val candidate = s"$firstSegment/$name"
      if (set.contains(candidate)) {
        throw new mill.api.daemon.Result.Exception(
          s"Circular moduleDeps detected: precompiled module '$ref' depends on itself",
          Some(mill.api.daemon.Result.Failure(
            s"Circular moduleDeps detected: precompiled module '$ref' depends on itself",
            scriptPath.toNIO,
            index
          ))
        )
      }
    }

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
   * Validate that all nested `object` keys in the YAML header correspond to actual
   * nested objects in the module class. Throws [[mill.api.daemon.Result.Exception]]
   * if a mismatch is found, which gets caught by the resolution chain's
   * `ExecResult.catchWrapException` and surfaced as a proper error.
   */
  private def validateNestedConfigKeys(
      module: mill.api.Module,
      scriptFile: os.Path,
      headerData: HeaderData
  ): Unit = {
    val nestedObjects = HeaderData
      .processRest(scriptFile, headerData)(
        onProperty = (_, _) => Seq.empty[(Located[String], String)],
        onNestedObject = (locatedKey, name, _) => Seq((locatedKey, name))
      )
      .flatten

    for ((locatedKey, name) <- nestedObjects) {
      val hasMethod =
        try { module.getClass.getMethod(name); true }
        catch { case _: NoSuchMethodException => false }
      if (!hasMethod) {
        throw new mill.api.daemon.Result.Exception(
          s"Config key ${pprint.Util.literalize("object " + name)} in " +
            s"${scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)} " +
            s"does not match any nested module in ${module.getClass.getName}",
          Some(mill.api.daemon.Result.Failure(
            s"Config key ${pprint.Util.literalize("object " + name)} " +
              s"does not match any nested module in ${module.getClass.getName}",
            scriptFile.toNIO,
            locatedKey.index
          ))
        )
      }
    }
  }

  def apply(
      parent: mill.api.Module,
      relPath: String,
      extendsClass: String,
      moduleDeps0: () => Map[String, Seq[mill.api.Module]],
      compileModuleDeps0: () => Map[String, Seq[mill.api.Module]],
      runModuleDeps0: () => Map[String, Seq[mill.api.Module]],
      bomModuleDeps0: () => Map[String, Seq[mill.api.Module]]
  ): mill.api.Module = {
    val scriptFile = os.Path(relPath, mill.api.BuildCtx.workspaceRoot)
    cache.getOrElseUpdate(scriptFile, {
      val set = constructing.get()
      set.add(relPath)
      try {
        // Evaluate dep maps now that `relPath` is in the `constructing` set,
        // so that self-referential deps are detected instead of deadlocking.
        val moduleDeps = moduleDeps0()
        val compileModuleDeps = compileModuleDeps0()
        val runModuleDeps = runModuleDeps0()
        val bomModuleDeps = bomModuleDeps0()
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
        val module =
          cls.getDeclaredConstructors.head.newInstance(config).asInstanceOf[mill.api.Module]
        validateNestedConfigKeys(module, scriptFile, headerData)
        module
      } finally {
        set.remove(relPath)
      }
    })
  }
}
