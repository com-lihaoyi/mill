package mill.api

/**
 * Helper object for resolving module deps from string identifiers at runtime.
 * Used by YAML builds to defer module resolution from codegen time to runtime.
 * Configuration is loaded from a classpath resource file written during code generation.
 */
object ModuleDepsResolver {

  /** Configuration entry for a single moduleDeps field */
  case class ModuleDepsEntry(deps: Seq[String], append: Boolean)
  object ModuleDepsEntry {
    implicit val rw: upickle.default.ReadWriter[ModuleDepsEntry] = upickle.default.macroRW
  }

  /** Configuration for all moduleDeps fields of a module */
  case class ModuleDepsConfig(
      moduleDeps: Option[ModuleDepsEntry] = None,
      compileModuleDeps: Option[ModuleDepsEntry] = None,
      runModuleDeps: Option[ModuleDepsEntry] = None,
      bomModuleDeps: Option[ModuleDepsEntry] = None
  )
  object ModuleDepsConfig {
    implicit val rw: upickle.default.ReadWriter[ModuleDepsConfig] = upickle.default.macroRW
  }

  // Lazily load configuration from classpath resource
  private lazy val configFromClasspath: Map[String, ModuleDepsConfig] = {
    val resourcePath = "mill/module-deps-config.json"
    val classLoader = Thread.currentThread().getContextClassLoader
    Option(classLoader.getResourceAsStream(resourcePath)) match {
      case Some(stream) =>
        try {
          val content = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
          upickle.default.read[Map[String, ModuleDepsConfig]](content)
        } finally {
          stream.close()
        }
      case None =>
        Map.empty
    }
  }

  /**
   * Resolves module deps from classpath configuration at runtime.
   *
   * @param rootModule The root module to use for resolving paths
   * @param modulePath The path of the module requesting resolution (e.g., "sub", "foo.bar")
   * @param fieldName The name of the field being resolved (e.g., "moduleDeps", "compileModuleDeps")
   * @param default The default module deps (from super.moduleDeps)
   * @return The resolved modules, either replacing or appending to default
   */
  def resolveModuleDeps[T <: Module](
      rootModule: Module,
      modulePath: String,
      fieldName: String,
      default: => Seq[T]
  ): Seq[T] = {
    val config = configFromClasspath.getOrElse(modulePath,
      throw new IllegalStateException(
        s"No module deps configuration found for module '$modulePath'. " +
          s"Available: ${configFromClasspath.keys.mkString(", ")}"
      )
    )

    val entry = fieldName match {
      case "moduleDeps" => config.moduleDeps
      case "compileModuleDeps" => config.compileModuleDeps
      case "runModuleDeps" => config.runModuleDeps
      case "bomModuleDeps" => config.bomModuleDeps
      case _ => throw new IllegalArgumentException(s"Unknown field name: $fieldName")
    }

    entry match {
      case None => default
      case Some(ModuleDepsEntry(deps, append)) =>
        if (deps.isEmpty) {
          if (append) default else Seq.empty
        } else {
          val segmentsToModules = rootModule.moduleInternal.segmentsToModules

          val resolved = deps.flatMap { depString =>
            // Convert path string to Segments
            // "build" -> empty segments (root module)
            // "foo.bar" -> Segments(Label("foo"), Label("bar"))
            val segments =
              if (depString == "build") Segments()
              else Segments.labels(depString.split('.').toIndexedSeq*)

            segmentsToModules.get(segments) match {
              case Some(module) => Some(module.asInstanceOf[T])
              case None =>
                throw new IllegalArgumentException(
                  s"Failed to resolve module dep '$depString': module not found. " +
                    s"Available modules: ${segmentsToModules.keys.map(_.render).mkString(", ")}"
                )
            }
          }

          if (append) default ++ resolved else resolved
        }
    }
  }
}
