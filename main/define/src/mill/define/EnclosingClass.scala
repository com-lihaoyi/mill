package mill.define

import scala.quoted.*

case class EnclosingClass(value: Class[_])
object EnclosingClass {
  def apply()(implicit c: EnclosingClass) = c.value
  inline given generate: EnclosingClass = ${ impl }

  // TODO: copied from Task.scala
  private def withMacroOwner[T](using quotes: Quotes)(op: quotes.reflect.Symbol => T): T = {
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

  def impl(using quotes: Quotes): Expr[EnclosingClass] = withMacroOwner { owner =>
    import quotes.reflect.*

    def enclosingClass(sym: Symbol): Symbol =
      if sym.isPackageDef || sym == Symbol.noSymbol then
        report.errorAndAbort(
          "Cannot find the enclosing class of the macro expansion",
          Position.ofMacroExpansion
        )
      else if sym.isClassDef then sym
      else enclosingClass(sym.owner)

    val cls = enclosingClass(owner).typeRef
    val res =
      '{ new EnclosingClass(${ Ref(defn.Predef_classOf).appliedToType(cls).asExprOf[Class[?]] }) }
    res
  }
}
