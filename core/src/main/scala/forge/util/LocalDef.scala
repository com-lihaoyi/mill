package forge.util

import scala.reflect.macros.blackbox
import language.experimental.macros
class LocalDef
object LocalDef {
  implicit def default: LocalDef = macro enclosing
  def enclosing(c: blackbox.Context): c.Expr[LocalDef] = {

    import c.universe._
    val current = c.internal.enclosingOwner

    if (
      !current.isMethod ||
      // We can't do this right now because it causes recursive method errors
      // current.asMethod.paramLists.nonEmpty ||
      !(current.owner.isClass || current.owner.isModuleClass)
    ) {
      c.abort(
        c.enclosingPosition,
        "T{} can only be used directly within a zero-arg method defined in a class body"
      )
    }else{

      c.Expr[LocalDef](q"""new forge.util.LocalDef()""")
    }
  }
}
