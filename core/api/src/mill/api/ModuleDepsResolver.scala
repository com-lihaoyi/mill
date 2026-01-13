package mill.api

import mill.api.daemon.internal.internal

import scala.quoted.*

/**
 * Helper object for resolving module deps from string identifiers at runtime.
 * Used by YAML builds to defer module resolution from codegen time to runtime.
 * Configuration is loaded from a classpath resource file written during code generation.
 */
@internal object ModuleDepsResolver {

  /**
   * Configuration entry for a single moduleDeps field.
   * @param deps List of (module path string, character offset in YAML file) pairs
   * @param append If true, append to super.moduleDeps; if false, replace it
   */
  case class ModuleDepsEntry(deps: Seq[(String, Int)], append: Boolean)
  object ModuleDepsEntry {
    implicit val rw: upickle.default.ReadWriter[ModuleDepsEntry] = upickle.default.macroRW
  }

  /** Configuration for all moduleDeps fields of a module */
  case class ModuleDepsConfig(
      yamlPath: String,
      moduleDeps: ModuleDepsEntry,
      compileModuleDeps: ModuleDepsEntry,
      runModuleDeps: ModuleDepsEntry,
      bomModuleDeps: ModuleDepsEntry
  )
  object ModuleDepsConfig {
    implicit val rw: upickle.default.ReadWriter[ModuleDepsConfig] = upickle.default.macroRW
  }

  /**
   * Macro that returns super.methodName if the enclosing class has a parent with that method,
   * otherwise returns Seq.empty. Used by generated code to avoid requiring override keyword.
   */
  inline def superMethod[T <: Module](inline methodName: String): Seq[T] =
    ${ superMethodImpl[T]('methodName) }

  private def superMethodImpl[T <: Module: Type](methodNameExpr: Expr[String])(using
      Quotes
  ): Expr[Seq[T]] = {
    val methodName = methodNameExpr.valueOrAbort
    import quotes.reflect.*

    // Find the enclosing class/trait
    var enclosingClass = Symbol.spliceOwner
    while (!enclosingClass.isClassDef && enclosingClass != Symbol.noSymbol) {
      enclosingClass = enclosingClass.owner
    }

    // Look for the method in base classes (excluding the current class)
    val baseClasses = enclosingClass.typeRef.baseClasses.drop(1)
    val methodSymOpt = baseClasses.flatMap(_.declaredMethod(methodName)).headOption

    methodSymOpt match {
      case Some(methodSym) =>
        // Generate: super.methodName.asInstanceOf[Seq[T]]
        val thisRef = This(enclosingClass)
        val superRef = Super(thisRef, None)
        val selectExpr = superRef.select(methodSym)
        selectExpr.asExpr.asInstanceOf[Expr[Seq[T]]]
      case None =>
        '{ Seq.empty[T] }
    }
  }

  def resolveModuleDeps[T <: Module](
      rootModule: Module,
      modulePath: String,
      fieldName: String,
      default: => Seq[T]
  ): Seq[T] = {
    // Load config using the build's classloader (accessed via rootModule).
    // We can't cache this because ModuleDepsResolver is in core/api which uses Mill's
    // classloader, not the build's classloader that has the config resource.
    val classLoader = rootModule.getClass.getClassLoader
    val content = os.read(os.resource(classLoader) / "mill/module-deps-config.json")
    val configFromClasspath = upickle.default.read[Map[String, ModuleDepsConfig]](content)

    // If no config found for this module path, return default (no override specified in YAML)
    val config = configFromClasspath(modulePath)

    val entry = fieldName match {
      case "moduleDeps" => config.moduleDeps
      case "compileModuleDeps" => config.compileModuleDeps
      case "runModuleDeps" => config.runModuleDeps
      case "bomModuleDeps" => config.bomModuleDeps
    }

    val ModuleDepsEntry(deps, append) = entry

    // If no deps specified and not appending, use default (super value)
    // This handles cases where the YAML doesn't specify moduleDeps at all
    if (deps.isEmpty && !append) default
    else {
      val segmentsToModules = rootModule.moduleInternal.segmentsToModules

      val resolved = deps.flatMap { case (depString, charOffset) =>
        val segments = Segments.labels(
          depString.split('.').toIndexedSeq match {
            case Seq("build", rest*) => rest
            case all => all
          }*
        )

        segmentsToModules.get(segments) match {
          case Some(module) => Some(module.asInstanceOf[T])
          case None =>
            val available = segmentsToModules.keys.map(_.render).mkString(", ")
            val msg = s"Cannot resolve moduleDep '$depString'. Available modules: $available"
            throw new Result.Exception(
              msg,
              Some(Result.Failure(
                msg,
                path = java.nio.file.Path.of(config.yamlPath),
                index = charOffset
              ))
            )
        }
      }

      if (append) default ++ resolved else resolved
    }
  }
}
