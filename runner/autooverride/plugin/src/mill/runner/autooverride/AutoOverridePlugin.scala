package mill.runner.autooverride

import dotty.tools.dotc.*
import core.*
import Contexts.*
import Symbols.*
import Flags.*
import Types.*
import Decorators.*
import ast.tpd
import plugins.*

/**
 * Scala 3 compiler plugin that automatically implements abstract methods
 * for objects extending AutoOverride[T].
 */
class AutoOverridePlugin extends StandardPlugin {
  val name: String = "auto-override"
  val description: String = "Automatically implements abstract methods by delegating to autoOverrideImpl()"

  override def init(options: List[String]): List[PluginPhase] = {
    new AutoOverridePhase :: Nil
  }
}

/**
 * Compiler phase that implements the auto-override logic.
 * This phase runs after typer but before refchecks to ensure abstract methods
 * are implemented before the compiler checks for unimplemented members.
 */
class AutoOverridePhase extends PluginPhase {
  import tpd.*

  val phaseName = "auto-override"

  override val runsAfter = Set("typer")
  override val runsBefore = Set("refchecks")

  override def transformTypeDef(tree: TypeDef)(using Context): Tree = {
    tree match {
      case td @ TypeDef(_, template: Template) =>
        val cls = td.symbol

        // Only process module classes (objects)
        if (!cls.is(ModuleClass)) return tree

        // Check if this class extends AutoOverride[_]
        val autoOverrideOpt = findAutoOverrideTrait(cls)
        if (autoOverrideOpt.isEmpty) return tree

        val (_, typeArg) = autoOverrideOpt.get

        // Find the autoOverrideImpl method
        val autoOverrideImplSym = cls.info.member("autoOverrideImpl".toTermName).symbol
        if (!autoOverrideImplSym.exists) {
          report.error(s"Object ${cls.name} extends AutoOverride but does not implement autoOverrideImpl()", tree.sourcePos)
          return tree
        }

        // Find all abstract methods that need to be implemented
        val abstractMethods = findAbstractMethodsToImplement(cls, typeArg)

        if (abstractMethods.isEmpty) return tree

        // Generate implementations for each abstract method
        val newDefs = abstractMethods.map { method =>
          generateMethodImpl(method, autoOverrideImplSym, cls)
        }

        // Add the new method implementations to the template
        val newTemplate = cpy.Template(template)(body = template.body ++ newDefs)
        cpy.TypeDef(tree)(rhs = newTemplate)

      case _ => tree
    }
  }

  /**
   * Finds the AutoOverride trait in the class hierarchy and extracts its type argument.
   */
  private def findAutoOverrideTrait(cls: Symbol)(using Context): Option[(Symbol, Type)] = {
    // First check direct parents
    cls.info.parents.collectFirst {
      case AppliedType(tycon, args) if tycon.typeSymbol.name.toString.contains("AutoOverride") && args.nonEmpty =>
        (tycon.typeSymbol, args.head)
    }.orElse {
      // If not found in direct parents, check all base classes
      cls.info.baseClasses.find { base =>
        base.name.toString.contains("AutoOverride")
      }.flatMap { baseClass =>
        // Get the type argument from AutoOverride[T] via baseType
        val baseTypeRef = cls.asClass.typeRef.baseType(baseClass)
        baseTypeRef match {
          case AppliedType(tycon, args) if args.nonEmpty =>
            Some((baseClass, args.head))
          case _ => None
        }
      }
    }
  }

  /**
   * Finds all abstract methods with return type <: T that need to be implemented.
   */
  private def findAbstractMethodsToImplement(cls: Symbol, returnType: Type)(using Context): List[Symbol] = {
    cls.info.abstractTermMembers.filter { member =>
      // Must be a method
      member.symbol.is(Method) &&
      // Must be abstract
      member.symbol.is(Deferred) &&
      // Must not be autoOverrideImpl itself
      member.name.toString != "autoOverrideImpl" &&
      // Return type must be a subtype of T
      member.info.finalResultType <:< returnType
    }.toList.map(_.symbol)
  }

  /**
   * Generates the implementation for an abstract method that calls autoOverrideImpl().
   */
  private def generateMethodImpl(method: Symbol, autoOverrideImplSym: Symbol, cls: Symbol)(using Context): DefDef = {
    val meth = method.asTerm

    // Create a new symbol for the concrete implementation (remove Deferred flag)
    val newFlags = (meth.flags &~ Deferred) | Override
    val newSym = newSymbol(
      cls,
      meth.name,
      newFlags,
      meth.info,
      coord = meth.coord
    ).asTerm

    // Set the symbol as entered in the owner's scope
    cls.asClass.enter(newSym)

    // Generate the method body: this.autoOverrideImpl()
    val thisRef = This(cls.asClass)
    val callAutoOverride = thisRef.select(autoOverrideImplSym).appliedToNone

    // Create the DefDef with the generated body
    DefDef(newSym, callAutoOverride)
  }
}
