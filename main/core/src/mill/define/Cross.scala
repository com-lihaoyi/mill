package mill.define
import language.experimental.macros
import scala.reflect.ClassTag
import scala.reflect.macros.blackbox

object Cross {
  case class Factory[T](make: (Product, mill.define.Ctx, Seq[Product]) => T)

  object Factory {
    implicit def make[T]: Factory[T] = macro makeImpl[T]
    def makeImpl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[Factory[T]] = {
      import c.universe._
      val tpe = weakTypeOf[T]

      val primaryConstructorArgs =
        tpe.typeSymbol.asClass.primaryConstructor.typeSignature.paramLists.head

      val argTupleValues =
        for ((a, n) <- primaryConstructorArgs.zipWithIndex)
          yield q"v.productElement($n).asInstanceOf[${a.info}]"

      val instance = c.Expr[(Product, mill.define.Ctx, Seq[Product]) => T](
        q"""{ (v, ctx0, vs) => new $tpe(..$argTupleValues){
          override def millOuterCtx = ctx0.copy(
            crossInstances = vs.map(v => new $tpe(..$argTupleValues))
          )
        } }"""
      )

      reify { mill.define.Cross.Factory[T](instance.splice) }
    }
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
class Cross[T <: Module : ClassTag](cases: Any*)(implicit ci: Cross.Factory[T], ctx: mill.define.Ctx)
    extends mill.define.Module()(ctx) {

  // TODO: change to Seq[Module] in 0.11
  override lazy val millModuleDirectChildren: IndexedSeq[Module] =
    this.millInternal.reflectNestedObjects[Module].toIndexedSeq ++
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
      ctx.copy(
        segments = ctx.segments ++ Seq(ctx.segment),
        millSourcePath = ctx.millSourcePath / relPath,
        segment = Segment.Cross(crossValues)
      ),
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
