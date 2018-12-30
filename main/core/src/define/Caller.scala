package mill.define

import sourcecode.Compat.Context
import language.experimental.macros
case class Caller(value: Any)
object Caller {
  def apply()(implicit c: Caller) = c.value
  implicit def generate: Caller = macro impl
  def impl(c: Context): c.Tree = {
    import c.universe._
    q"new _root_.mill.define.Caller(this)"
  }
}