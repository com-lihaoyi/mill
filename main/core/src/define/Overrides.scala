package mill.define
import scala.reflect.macros.blackbox.Context
import language.experimental.macros
case class Overrides(value: Int)
object Overrides{
  def apply()(implicit c: Overrides) = c.value
  implicit def generate: Overrides = macro impl
  def impl(c: Context): c.Tree = {
    import c.universe._
    q"new _root_.mill.define.Overrides(${c.internal.enclosingOwner.overrides.length})"
  }
}
