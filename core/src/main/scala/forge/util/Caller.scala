package forge.util

import scala.reflect.macros.blackbox
import language.experimental.macros
case class Caller(value: Any)
object Caller {
  implicit def generate: Caller = macro impl
  def impl(c: blackbox.Context): c.Tree = {
    import c.universe._
    q"new _root_.forge.util.Caller(this)"
  }
}
