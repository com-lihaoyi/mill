package mill.define

import scala.quoted.*

case class EnclosingClass(value: Class[?])
object EnclosingClass {
  def apply()(implicit c: EnclosingClass) = c.value
  inline given generate: EnclosingClass = ${ impl }

  def impl(using quotes: Quotes): Expr[EnclosingClass] = Cacher.withMacroOwner { owner =>
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
