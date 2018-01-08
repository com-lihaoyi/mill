package mill.define
import language.experimental.macros
import scala.reflect.macros.blackbox


object Cross{
  case class Factory[T](make: (Product, Module.Ctx) => T)

  object Factory{
    implicit def make[T]: Factory[T] = macro makeImpl[T]
    def makeImpl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[Factory[T]] = {
      import c.universe._
      val tpe = weakTypeOf[T]

      val primaryConstructorArgs =
        tpe.typeSymbol.asClass.primaryConstructor.typeSignature.paramLists.head

      val argTupleValues =
        for((a, n) <- primaryConstructorArgs.zipWithIndex)
        yield q"v.productElement($n).asInstanceOf[${a.info}]"

      val instance = c.Expr[(Product, Module.Ctx) => T](
        q"{ (v, ctx0) => new $tpe(..$argTupleValues){  override def ctx = ctx0 } }"
      )

      reify { mill.define.Cross.Factory[T](instance.splice) }
    }
  }
}
class Cross[T](cases: Any*)
              (implicit ci: Cross.Factory[T],
               val ctx: Module.Ctx){

  val items = for(c0 <- cases.toList) yield{
    val c = c0 match{
      case p: Product => p
      case v => Tuple1(v)
    }
    val crossValues = c.productIterator.toList.reverse
    val sub = ci.make(
      c,
      ctx.copy(
        segments0 = ctx.segments0 ++ Seq(ctx.segment),
        segment = Segment.Cross(crossValues)
      )
    )
    (crossValues, sub)
  }
  val itemMap = items.toMap
  def apply(args: Any*) = itemMap(args.toList)
}