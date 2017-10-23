package forge


import scala.language.experimental.macros
import scala.reflect.macros._


sealed abstract class DefCtx(val value: Option[String])
object DefCtx{
  implicit object Anonymous extends DefCtx(None)
  case class Labeled(label: String) extends DefCtx(Some(label))
}

object T{
  def apply[T](expr: T): T = macro applyImpl[T]

  def applyImpl[T: c.WeakTypeTag](c: Context)(expr: c.Expr[T]): c.Expr[T] = {
    import c.universe._
    val transformed = expr.tree match{
      case Apply(fun, args) =>
        var transformed = false
        val newArgs = args.map{
          case x if x.tpe == weakTypeOf[DefCtx.Anonymous.type] =>
            transformed = true
            q"forge.DefCtx.Labeled(sourcecode.Enclosing())"
          case x => x
        }
        assert(transformed)
        Apply(fun, newArgs)
      case _ => ???
    }
    c.Expr[T](transformed)
  }
}