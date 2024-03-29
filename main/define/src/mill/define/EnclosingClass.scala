package mill.define

import sourcecode.Compat.Context
import language.experimental.macros
case class EnclosingClass(value: Class[_])
object EnclosingClass {
  def apply()(implicit c: EnclosingClass) = c.value
  implicit def generate: EnclosingClass = macro impl
  def impl(c: Context): c.Tree = {
    import c.universe._
    //    q"new _root_.mill.define.EnclosingClass(classOf[$cls])"
    q"new _root_.mill.define.EnclosingClass(this.getClass)"
  }
}
