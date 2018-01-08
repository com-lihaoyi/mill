package mill.define
import language.experimental.macros
import scala.reflect.macros.blackbox


object Cross{
  abstract class Implicit[T]{
    def make(v: Any, ctx: Module.Ctx): T
    def crossValues(v: Any): List[Any]
  }
  object Implicit{
    implicit def make[T]: Implicit[T] = macro makeImpl[T]
    def makeImpl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[Implicit[T]] = {
      import c.universe._
      val tpe = weakTypeOf[T]

      val primaryConstructorArgs =
        tpe.typeSymbol.asClass.primaryConstructor.typeSignature.paramLists.head

      val tree = primaryConstructorArgs match{
        case List(arg) =>
          q"""
            new mill.define.Cross.Implicit[$tpe]{
              def make(v: Any, ctx0: mill.define.Module.Ctx) = new $tpe(v.asInstanceOf[${arg.info}]){
                override def ctx = ctx0
              }
              def crossValues(v: Any) = List(v)
            }
          """
        case args =>
          val argTupleValues = for((a, n) <- args.zipWithIndex) yield{
            q"v.asInstanceOf[scala.Product].productElement($n).asInstanceOf[${a.info}]"
          }
          q"""
            new mill.define.Cross.Implicit[$tpe]{
              def make(v: Any, ctx0: mill.define.Module.Ctx) = new $tpe(..$argTupleValues){
                override def ctx = ctx0
              }
              def crossValues(v: Any) = List(..$argTupleValues)
            }
          """

      }
      c.Expr[Implicit[T]](tree)
    }
  }
}
class Cross[T](cases: Any*)
                    (implicit ci: Cross.Implicit[T],
                     val ctx: Module.Ctx){
  val items = for(c <- cases.toList) yield{
    val crossValues = ci.crossValues(c)
    val sub = ci.make(
      c,
      ctx.copy(
        segments0 = ctx.segments0 ++ Seq(ctx.segment),
        segment = Segment.Cross(crossValues.reverse)
      )
    )
    (crossValues.reverse, sub)
  }
  val itemMap = items.toMap
  def apply(args: Any*) = itemMap(args.toList)
}