package mill.define

import scala.reflect.macros.blackbox.Context
import language.experimental.macros

case class DefiningClass(value Class[_])
object DefiningClass {
  def apply()(implicit c: DefiningClass) = c.value
  implicit def generate: DefiningClass = macro impl
  def impl(c: Context): c.Tree = {
    import c.universe._
    println("c.internal.enclosingOwner " + c.internal.enclosingOwner)
    mill.main.client.DebugLog.println("c.internal.enclosingOwner " + c.internal.enclosingOwner)
    q"new _root_.mill.define.DefiningClass(classOf[${c.internal.enclosingOwner.name}])"
  }
}
