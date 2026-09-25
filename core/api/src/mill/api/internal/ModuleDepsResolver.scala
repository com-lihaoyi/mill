package mill.api.internal

import mill.api.{Module, ModuleRef, Result, Segment, Segments}
import mill.api.daemon.internal.internal

import scala.quoted.*
import scala.reflect.ClassTag

/**
 * Helper object for resolving module deps from string identifiers at runtime.
 * Used by YAML builds to defer module resolution from codegen time to runtime.
 * Configuration is loaded from a classpath resource file written during code generation.
 */
@internal object ModuleDepsResolver {

  /** A module reference string, and the character offset in the YAML file it came from. */
  case class ModuleLoc(ref: String, charOffset: Int) derives upickle.default.ReadWriter

  /**
   * Configuration entry for a single moduleDeps field.
   * @param deps The referenced modules
   * @param append If true, append to super.moduleDeps; if false, replace it
   */
  case class ModuleDepsEntry(deps: Seq[ModuleLoc], append: Boolean)
      derives upickle.default.ReadWriter

  /** Configuration for all moduleDeps fields of a module */
  case class ModuleDepsConfig(
      yamlPath: String,
      moduleDeps: ModuleDepsEntry,
      compileModuleDeps: ModuleDepsEntry,
      runModuleDeps: ModuleDepsEntry,
      bomModuleDeps: ModuleDepsEntry,
      androidSdkModule: Option[ModuleLoc]
  ) derives upickle.default.ReadWriter

  private lazy val configFromClasspath: Map[String, ModuleDepsConfig] = {
    val content =
      os.read(os.resource(using getClass.getClassLoader) / "mill/module-deps-config.json")
    upickle.default.read[Map[String, ModuleDepsConfig]](content)
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

  /**
   * Parses a module reference string using ParseArgs.extractSegments.
   * Handles both dot notation (qux.1) and bracket notation (qux[1]).
   */
  private def parseModuleRef(depString: String): Result[Segments] = {
    ParseArgs.extractSegments(depString).map { case (_, segments) =>
      // Strip leading "build" segment if present
      segments.value match {
        case Segment.Label("build") +: rest => Segments(rest)
        case _ => segments
      }
    }
  }

  /** Resolves and type-checks a single module reference string. */
  private def resolveModule[T <: Module](
      yamlPath: String,
      segmentsToModules: Map[Segments, Module],
      depString: String,
      charOffset: Int
  )(implicit ct: ClassTag[T]): T = {
    def fail(msg: String): Nothing =
      throw new Result.Exception(
        msg,
        Some(Result.Failure(msg, path = java.nio.file.Path.of(yamlPath), index = charOffset))
      )

    parseModuleRef(depString) match {
      case f: Result.Failure =>
        throw new Result.Exception(
          f.error,
          Some(f.copy(path = java.nio.file.Path.of(yamlPath), index = charOffset))
        )
      case Result.Success(segments) =>
        segmentsToModules.get(segments) match {
          case Some(module) if ct.runtimeClass.isInstance(module) =>
            module.asInstanceOf[T]
          case Some(module) =>
            val expectedType = ct.runtimeClass.getName
            val actualType = module.getClass.getName
            fail(s"Module '$depString' is a $actualType, not a $expectedType")
          case None =>
            val available = segmentsToModules.keys.map(_.render).mkString(", ")
            fail(s"Cannot resolve moduleDep '$depString'. Available modules: $available")
        }
    }
  }

  def resolveModules[T <: Module](
      rootModule: Module,
      modulePath: String,
      fieldName: String,
      default: => Seq[T]
  )(implicit ct: ClassTag[T]): Seq[T] = {
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
      val resolved = deps.map { case ModuleLoc(depString, charOffset) =>
        resolveModule[T](config.yamlPath, segmentsToModules, depString, charOffset)
      }
      if (append) default ++ resolved else resolved
    }
  }

  /** Resolves a required `ModuleRef` field */
  def resolveModuleRef[T <: Module](
      rootModule: Module,
      modulePath: String,
      fieldName: String
  )(implicit ct: ClassTag[T]): ModuleRef[T] = {
    val config = configFromClasspath(modulePath)

    val depOpt = fieldName match {
      case "androidSdkModule" => config.androidSdkModule
    }

    depOpt match {
      case None =>
        val msg = s"'$fieldName' must be configured"
        throw new Result.Exception(
          msg,
          Some(Result.Failure(msg, path = java.nio.file.Path.of(config.yamlPath), index = -1))
        )
      case Some(ModuleLoc(depString, charOffset)) =>
        val segmentsToModules = rootModule.moduleInternal.segmentsToModules
        ModuleRef(resolveModule[T](config.yamlPath, segmentsToModules, depString, charOffset))
    }
  }
}
