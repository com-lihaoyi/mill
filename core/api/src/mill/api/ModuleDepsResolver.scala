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

  def resolveModuleDeps[T <: Module](
      rootModule: Module,
      modulePath: String,
      fieldName: String,
      default: => Seq[T]
  ): Seq[T] = {
    // Load config fresh each time using the build's classloader (accessed via rootModule)
    // We can't cache this because ModuleDepsResolver is in core/api which uses Mill's
    // classloader, not the build's classloader that has the config resource
    val classLoader = rootModule.getClass.getClassLoader
    val content = os.read(os.resource(classLoader) / "mill/module-deps-config.json")
    val configFromClasspath = upickle.default.read[Map[String, ModuleDepsConfig]](content)

    // If no config found for this module path, return default (no override specified in YAML)
    val config = configFromClasspath.getOrElse(modulePath, return default)

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
            val segments = Segments.labels(
              depString.split('.').toIndexedSeq match{
                case Seq("build", rest*) => rest
                case all => all
              }
            )

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
