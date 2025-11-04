package mill.api.internal

import scala.collection.immutable.VectorMap
import scala.collection.mutable
import scala.util.DynamicVariable
import scala.quoted.*

trait Cacher extends mill.moduledefs.Cacher {
  private lazy val cacherLazyMap = mutable.Map.empty[sourcecode.Enclosing, Any]

  protected def cachedTask[T](t: => T)(using c: sourcecode.Enclosing): T = synchronized {
    if (Cacher.taskEvaluationStack.value.contains((c, this))) {
      sys.error(
        "Circular task dependency detected:\n" +

          (Cacher.taskEvaluationStack.value.keys ++ Seq((c, this)))
            .map { case (c, o) =>
              val taskName = c.value.split("\\.|#| ").filter(!_.startsWith("$anon")).last
              o.toString match {
                case "" => taskName
                case s => s + "." + taskName
              }
            }
            .mkString("\ndepends on: ")
      )
    }

    Cacher.taskEvaluationStack.withValue(Cacher.taskEvaluationStack.value ++ Seq((c, this) -> ())) {
      cacherLazyMap.getOrElseUpdate(c, t).asInstanceOf[T]
    }
  }
}

private[mill] object Cacher {
  // Use a VectorMap for fast contains checking while preserving insertion order
  private[mill] val taskEvaluationStack =
    DynamicVariable[VectorMap[(sourcecode.Enclosing, Any), Unit]](VectorMap())
  private[mill] def withMacroOwner[T](using Quotes)(op: quotes.reflect.Symbol => T): T = {
    import quotes.reflect.*
    // In Scala 3, the top level splice of a macro is owned by a symbol called "macro" with the macro flag set,
    // but not the method flag.
    def isMacroOwner(sym: Symbol): Boolean =
      sym.name == "macro" && sym.flags.is(Flags.Macro | Flags.Synthetic) && !sym.flags.is(
        Flags.Method
      )

    def loop(owner: Symbol): T =
      if owner.isPackageDef || owner == Symbol.noSymbol then
        report.errorAndAbort(
          "Cannot find the owner of the macro expansion",
          Position.ofMacroExpansion
        )
      else if isMacroOwner(owner) then op(owner.owner) // Skip the "macro" owner
      else loop(owner.owner)

    loop(Symbol.spliceOwner)
  }

  def impl0[T: Type](using Quotes)(t: Expr[T]): Expr[T] = withMacroOwner { owner =>
    import quotes.reflect.*

    val CacherSym = TypeRepr.of[Cacher].typeSymbol

    val ownerIsCacherClass =
      owner.owner.isClassDef &&
        owner.owner.typeRef.baseClasses.contains(CacherSym)

    if (ownerIsCacherClass && owner.flags.is(Flags.Method)) {
      val enclosingCtx = Expr.summon[sourcecode.Enclosing].getOrElse(
        report.errorAndAbort("Cannot find enclosing context", Position.ofMacroExpansion)
      )

      val thisSel = This(owner.owner).asExprOf[Cacher]
      '{ $thisSel.cachedTask[T](${ t })(using $enclosingCtx) }
    } else '{
      // Use a runtime exception to prevent false error highlighting in IntelliJ
      throw new Exception("Task{} members must be defs defined in a Module class/trait/object body")
    }
  }
}
