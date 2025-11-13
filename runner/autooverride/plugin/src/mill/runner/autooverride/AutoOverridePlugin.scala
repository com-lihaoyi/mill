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
  val description: String =
    "Automatically implements abstract methods by delegating to autoOverrideImpl()"

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
  override val runsBefore = Set("inlining")

  override def transformTypeDef(tree: TypeDef)(using Context): Tree = {
    tree match {
      case td @ TypeDef(_, template: Template) if td.symbol.is(ModuleClass) =>
        val cls = td.symbol
        findAutoOverrideTrait(cls) match {
          case None => tree
          case Some(typeArg) =>
            val abstractMethods = findAbstractMethodsToImplement(cls, typeArg)
            if (abstractMethods.isEmpty) tree
            else {
              val autoOverrideImplSym = cls.info.member("autoOverrideImpl".toTermName).symbol
              val newDefs = abstractMethods.map(generateMethodImpl(_, autoOverrideImplSym, cls))
              val newTemplate = cpy.Template(template)(body = template.body ++ newDefs)
              cpy.TypeDef(tree)(rhs = newTemplate)
            }
        }
      case _ => tree
    }
  }

  private def findAutoOverrideTrait(cls: Symbol)(using Context): Option[Type] = {
    cls.info.baseClasses
      .find(_.name.toString.endsWith("AutoOverride"))
      .flatMap { baseClass =>
        val baseTypeRef = cls.asClass.typeRef.baseType(baseClass)
        baseTypeRef match {
          case AppliedType(tycon, args) if args.nonEmpty =>
            Some(args.head)
          case _ => None
        }
      }
  }

  private def findAbstractMethodsToImplement(cls: Symbol, returnType: Type)(using
      Context
  ): List[Symbol] = {
    cls.info.abstractTermMembers.filter { member =>
      val name = member.name.toString
      member.symbol.is(Method) && // Must be a method
      member.symbol.is(Deferred) && // Must be abstract
      name != "autoOverrideImpl" && // Must not be autoOverrideImpl itself
      !name.contains("$super") && // Filter out synthetic super methods
      member.info.finalResultType <:< returnType // Return type must be a subtype of T
    }.toList.map(_.symbol)
  }

  private def generateMethodImpl(method: Symbol, autoOverrideImplSym: Symbol, cls: Symbol)(using
      Context
  ): DefDef = {
    val meth = method.asTerm

    val newFlags = (meth.flags &~ Deferred) | Override
    val newSym = newSymbol(cls, meth.name, newFlags, meth.info, coord = meth.coord).asTerm

    cls.asClass.enter(newSym)

    val thisRef = This(cls.asClass)
    val callAutoOverride = thisRef.select(autoOverrideImplSym)
      .appliedToTypes(List(meth.localReturnType))
      .appliedToNone

    DefDef(newSym, callAutoOverride)
  }
}
