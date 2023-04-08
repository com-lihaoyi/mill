package mill.define

import language.experimental.macros
import scala.reflect.ClassTag
import scala.reflect.macros.blackbox

object Cross {
  case class Factory[T](make: (Product, mill.define.Ctx, Seq[Product]) => T) {
    private def copy[T](make: (Product, mill.define.Ctx, Seq[Product]) => T = make): Factory[T] =
      new Factory[T](make)
  }

  object Factory {
    implicit def make[T]: Factory[T] = macro makeImpl[T]
    def makeImpl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[Factory[T]] = {
      import c.universe._
      val tpe = weakTypeOf[T]

      val instance = c.Expr[(Product, mill.define.Ctx, Seq[Product]) => T](
        if (tpe.typeSymbol.isClass) {
          if (tpe.typeSymbol.asClass.isTrait) {
            val v1 = c.freshName(TermName("v1"))
            val v2 = c.freshName(TermName("v2"))
            val vs = c.freshName(TermName("vs"))
            val ctx0 = c.freshName(TermName("ctx0"))
            q"""{ ($v1: ${tq""}, $ctx0: ${tq""}, $vs: ${tq""}) =>
              new $tpe{
                override def millCrossValue = $v1
                override def millOuterCtx = $ctx0.withCrossInstances(
                  $vs.map(($v2: ${tq""}) => new $tpe{ override def millCrossValue = $v2 })
                )
              }
            }"""
          } else {
            val primaryConstructorArgs =
              tpe.typeSymbol.asClass.primaryConstructor.typeSignature.paramLists.head

            val argTupleValues =
              for ((a, n) <- primaryConstructorArgs.zipWithIndex)
                yield q"v.productElement($n).asInstanceOf[${a.info}]"


            q"""{ (v, ctx0, vs) =>
                new $tpe(..$argTupleValues){
                  override def millOuterCtx = ctx0.withCrossInstances(
                    vs.map(v => new $tpe(..$argTupleValues))
                  )
                }
            }"""
          }
        }else{
          c.abort(c.enclosingPosition, "Cross[T] type must be class or trait")
        }
      )
      reify { mill.define.Cross.Factory[T](instance.splice) }

    }

    private def unapply[T](factory: Factory[T]): Option[(Product, Ctx, Seq[Product]) => T] =
      Some(factory.make)
  }

  trait Resolver[-T <: Module] {
    def resolve[V <: T](c: Cross[V]): V
  }
}

/**
 * Models "cross-builds": sets of duplicate builds which differ only in the
 * value of one or more "case" variables whose values are determined at runtime.
 * Used via:
 *
 * object foo extends Cross[FooModule]("bar", "baz", "qux")
 * class FooModule(v: String) extends Module{
 *   ...
 * }
 */
class Cross[T <: Module: ClassTag](cases: Any*)(implicit ci: Cross.Factory[T], ctx: mill.define.Ctx)
    extends mill.define.Module()(ctx) {

  override lazy val millModuleDirectChildren: Seq[Module] =
    super.millModuleDirectChildren ++
      items.collect { case (_, v: mill.define.Module) => v }

  private val products: List[Product] = cases.toList.map {
    case p: Product => p
    case v => Tuple1(v)
  }

  val items: List[(List[Any], T)] = for (c <- products) yield {
    val crossValues = c.productIterator.toList
    val relPath = ctx.segment.pathSegments
    val sub = ci.make(
      c,
      ctx
        .withSegments(ctx.segments ++ Seq(ctx.segment))
        .withMillSourcePath(ctx.millSourcePath / relPath)
        .withSegment(Segment.Cross(crossValues)),
      products
    )
    (crossValues, sub)
  }
  val itemMap: Map[List[Any], T] = items.toMap

  /**
   * Fetch the cross module corresponding to the given cross values
   */
  def get(args: Seq[Any]): T = itemMap(args.toList)

  /**
   * Fetch the cross module corresponding to the given cross values
   */
  def apply(arg0: Any, args: Any*): T = itemMap(arg0 :: args.toList)

  /**
   * Fetch the relevant cross module given the implicit resolver you have in
   * scope. This is often the first cross module whose cross-version is
   * compatible with the current module.
   */
  def apply[V >: T <: Module]()(implicit resolver: Cross.Resolver[V]): T = {
    resolver.resolve(this.asInstanceOf[Cross[V]]).asInstanceOf[T]
  }
}
