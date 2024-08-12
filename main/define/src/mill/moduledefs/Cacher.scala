// TODO DELETE THIS FILE WHEN WE PORT MODULEDEFS PLUGIN AND REINTRODUCE THE DEPENDENCY
package mill.moduledefs

import scala.collection.mutable
import scala.quoted.*

trait Cacher {
  private lazy val cacherLazyMap = mutable.Map.empty[sourcecode.Enclosing, Any]

  protected def cachedTarget[T](t: => T)(implicit c: sourcecode.Enclosing): T = synchronized {
    cacherLazyMap.getOrElseUpdate(c, t).asInstanceOf[T]
  }
}

object Cacher {
  // TODO: copied from Task.scala - but Cacher should be moved eventually to the mill-moduledefs repo
  private def withMacroOwner[T](using Quotes)(op: quotes.reflect.Symbol => T): T = {
    import quotes.reflect.*

    // In Scala 3, the top level splice of a macro is owned by a symbol called "macro" with the macro flag set,
    // but not the method flag.
    def isMacroOwner(sym: Symbol)(using Quotes): Boolean =
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

  def impl0[T: Type](using Quotes)(t: Expr[T]): Expr[T] = {
    import quotes.reflect.*
    wrapCached[T](t.asTerm).asExprOf[T]
  }
  def wrapCached[R: Type](using Quotes)(t: quotes.reflect.Tree): quotes.reflect.Tree =
    withMacroOwner { owner =>
      import quotes.reflect.*

      val CacherSym = TypeRepr.of[Cacher].typeSymbol

      val ownerIsCacherClass =
        owner.owner.isClassDef &&
          owner.owner.typeRef.baseClasses.contains(CacherSym)

      if (ownerIsCacherClass && owner.flags.is(Flags.Method)) {
        // q"this.cachedTarget[${weakTypeTag[R]}]($t)"
        val enclosingCtx = Expr.summon[sourcecode.Enclosing].getOrElse(
          report.errorAndAbort("Cannot find enclosing context", Position.ofMacroExpansion)
        )

        val thisSel = This(owner.owner).asExprOf[Cacher]
        val res = '{ $thisSel.cachedTarget[R](${ t.asExprOf[R] })(using $enclosingCtx) }.asTerm
        // report.errorAndAbort(s"cached ${res.show}", Position.ofMacroExpansion)
        res
      } else report.errorAndAbort(
        "T{} members must be defs defined in a Cacher class/trait/object body",
        Position.ofMacroExpansion
      )
    }
}
