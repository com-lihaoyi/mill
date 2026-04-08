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
   *
   * Uses ConcurrentHashMap for thread safety since lazy val initialization in generated
   * code and script module resolution may happen on different threads.
   */
  private[mill] val cache: java.util.concurrent.ConcurrentHashMap[os.Path, mill.api.Module] =
    new java.util.concurrent.ConcurrentHashMap()

  /** Scala-friendly cache access */
  private[mill] def cacheGet(key: os.Path): Option[mill.api.Module] =
    Option(cache.get(key))

  /**
   * Pluggable YAML header data parser. Set by ScriptModuleInit (in core/eval)
   * at the start of each evaluation cycle. This allows PrecompiledModuleRef
   * (in core/api) to parse YAML headers without depending on snakeyaml directly.
   */
  @volatile private[mill] var headerDataParser: os.Path => mill.api.Result[HeaderData] =
    _ => mill.api.Result.Success(HeaderData(rest = Map.empty))

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
    // Check if the target module (identified by its first segment, which maps to a
    // directory containing a precompiled YAML module) is currently being constructed
    // on this thread. This detects both direct self-references (A depends on A) and
    // indirect cycles (A depends on B, B depends on A) since each module in the chain
    // adds its path to `constructing` before evaluating its deps.
    val firstSegment = ref.split("\\.").head
    val set = constructing.get()
    for (name <- Seq("package.mill.yaml", "build.mill.yaml")) {
      val candidate = s"$firstSegment/$name"
      if (set.contains(candidate)) {
        // Build a human-readable cycle description from the constructing set
        val chain = set.toArray.map(_.toString).toSeq
        val cycleDesc =
          if (chain.size <= 1) s"precompiled module '$ref' depends on itself"
          else s"circular moduleDeps: ${chain.mkString(" -> ")} -> $candidate"
        throw new mill.api.daemon.Result.Exception(
          s"Circular moduleDeps detected: $cycleDesc",
          Some(mill.api.daemon.Result.Failure(
            s"Circular moduleDeps detected: $cycleDesc",
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
   * nested objects in the module class. Returns `None` if valid, or `Some(Failure)`
   * describing the first mismatch found.
   *
   * Shared between [[apply]] (CodeGen path) and ScriptModuleInit (script path).
   */
  private[mill] def findNestedConfigMismatch(
      module: mill.api.Module,
      scriptFile: os.Path,
      headerData: HeaderData
  ): Option[mill.api.daemon.Result.Failure] = {
    val nestedObjects = HeaderData
      .processRest(scriptFile, headerData)(
        onProperty = (_, _) => Seq.empty[(Located[String], String)],
        onNestedObject = (locatedKey, name, _) => Seq((locatedKey, name))
      )
      .flatten

    nestedObjects.collectFirst {
      case (locatedKey, name)
          if (try { module.getClass.getMethod(name); false }
          catch { case _: NoSuchMethodException => true }) =>
        mill.api.daemon.Result.Failure(
          s"Config key ${pprint.Util.literalize("object " + name)} " +
            s"does not match any nested module in ${module.getClass.getName}",
          path = scriptFile.toNIO,
          index = locatedKey.index
        )
    }
  }

  /**
   * Validates that a class is suitable for precompiled module instantiation:
   * not a trait, not abstract, and has a constructor taking `ScriptModule.Config`.
   *
   * Returns the valid constructor, or throws [[mill.api.daemon.Result.Exception]]
   * with a descriptive error message.
   *
   * Shared between [[apply]] (CodeGen path) and ScriptModuleInit (script path).
   */
  private[mill] def validatePrecompiledClass(
      cls: Class[?],
      relPath: String,
      scriptFile: os.Path,
      extendsIndex: Int
  ): java.lang.reflect.Constructor[?] = {
    def fail(msg: String): Nothing =
      throw new mill.api.daemon.Result.Exception(
        msg,
        Some(mill.api.daemon.Result.Failure(msg, scriptFile.toNIO, extendsIndex))
      )

    if (cls.isInterface) {
      fail(
        s"Precompiled module '$relPath' extends '${cls.getName}' which is a trait. " +
          s"Precompiled modules must extend a class with a " +
          s"(val scriptConfig: mill.api.PrecompiledModule.Config) constructor parameter"
      )
    }
    if (java.lang.reflect.Modifier.isAbstract(cls.getModifiers)) {
      fail(
        s"Precompiled module '$relPath' extends '${cls.getName}' which is abstract. " +
          s"Precompiled modules must extend a concrete class with a " +
          s"(val scriptConfig: mill.api.PrecompiledModule.Config) constructor parameter"
      )
    }
    val constructors = cls.getDeclaredConstructors
    val validCtor = constructors.find { ctor =>
      val params = ctor.getParameterTypes
      params.length == 1 && params(0).isAssignableFrom(classOf[mill.api.ScriptModule.Config])
    }
    validCtor match {
      case Some(ctor) => ctor
      case None =>
        val actualSig = constructors.map { ctor =>
          ctor.getParameterTypes.map(_.getSimpleName).mkString("(", ", ", ")")
        }.mkString(", ")
        fail(
          s"Precompiled module '$relPath' extends '${cls.getName}' which does not have " +
            s"a (val scriptConfig: mill.api.PrecompiledModule.Config) constructor parameter. " +
            s"Found constructor(s): $actualSig"
        )
    }
  }

  def apply(
      @scala.annotation.unused parent: mill.api.Module,
      relPath: String,
      extendsClass: String,
      moduleDeps0: () => Map[String, Seq[mill.api.Module]],
      compileModuleDeps0: () => Map[String, Seq[mill.api.Module]],
      runModuleDeps0: () => Map[String, Seq[mill.api.Module]],
      bomModuleDeps0: () => Map[String, Seq[mill.api.Module]]
  ): mill.api.Module = {
    val scriptFile = os.Path(relPath, mill.api.BuildCtx.workspaceRoot)
    // Use computeIfAbsent for thread-safe atomic cache population
    cache.computeIfAbsent(
      scriptFile,
      _ => {
        val set = constructing.get()
        set.add(relPath)
        try {
          // Evaluate dep maps now that `relPath` is in the `constructing` set,
          // so that self-referential deps are detected instead of deadlocking.
          val moduleDeps = moduleDeps0()
          val compileModuleDeps = compileModuleDeps0()
          val runModuleDeps = runModuleDeps0()
          val bomModuleDeps = bomModuleDeps0()
          val headerData = headerDataParser(scriptFile) match {
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
          val extendsIndex = headerData.`extends`.index
          val validCtor = validatePrecompiledClass(cls, relPath, scriptFile, extendsIndex)
          val module = validCtor.newInstance(config).asInstanceOf[mill.api.Module]
          findNestedConfigMismatch(module, scriptFile, headerData).foreach { f =>
            throw new mill.api.daemon.Result.Exception(
              s"${f.error} in ${scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)}",
              Some(f)
            )
          }
          module
        } finally {
          set.remove(relPath)
        }
      }
    )
  }
}
