package mill.define

import language.experimental.macros
import scala.reflect.ClassTag
import scala.reflect.macros.blackbox

object Cross {
  trait Module[V] extends mill.define.Module {
    def millCrossValue: V
  }
  case class Factory[T, -V](make: (V, mill.define.Ctx, Seq[V]) => T)

  object Factory {
    implicit def make[T]: Factory[T, Any] = macro makeImpl[T]
    def makeImpl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[Factory[T, Any]] = {
      import c.universe._
      val tpe = weakTypeOf[T]

      if (tpe.typeSymbol.isClass) {
        if (tpe.typeSymbol.asClass.isTrait) {
          val crossType = tpe.baseType(typeOf[Module[_]].typeSymbol).typeArgs.head
          val v1 = c.freshName(TermName("v1"))
          val v2 = c.freshName(TermName("v2"))
          val vs = c.freshName(TermName("vs"))
          val ctx0 = c.freshName(TermName("ctx0"))
          val implicitCtx = c.freshName(TermName("implicitCtx"))
          val tree =q"""mill.define.Cross.Factory[$tpe, $crossType]{ ($v1: $crossType, $ctx0: ${tq""}, $vs: Seq[$crossType]) =>
            implicit val $implicitCtx = $ctx0
            new $tpe{
              override def millCrossValue = $v1
              override def millOuterCtx = $ctx0.withCrossInstances(
                $vs.map(($v2: $crossType) => new $tpe{ override def millCrossValue = $v2 })
              )
            }
          }.asInstanceOf[${weakTypeOf[Factory[T, Any]]}]"""

          c.Expr[Factory[T, Any]](tree)
        } else {
          val primaryConstructorArgs =
            tpe.typeSymbol.asClass.primaryConstructor.typeSignature.paramLists.head

          val argTupleValues =
            for ((a, n) <- primaryConstructorArgs.zipWithIndex)
            yield q"(v match { case p: Product => p case v => Tuple1(v)}).productElement($n).asInstanceOf[${a.info}]"


          val instance = c.Expr[(Any, mill.define.Ctx, Seq[Any]) => T](
            q"""{ (v, ctx0, vs) => new $tpe(..$argTupleValues){
              override def millOuterCtx = ctx0.withCrossInstances(
                vs.map(v => new $tpe(..$argTupleValues))
              )
            } }"""
          )
          reify { mill.define.Cross.Factory[T, Any](instance.splice) }
        }
      }else{
        c.abort(c.enclosingPosition, "Cross[T] type must be class or trait")
      }
    }
  }

  trait Resolver[-T <: mill.define.Module] {
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
class Cross[T <: Module: ClassTag](cases: Any*)(implicit ci: Cross.Factory[T, Any], ctx: mill.define.Ctx)
    extends mill.define.Module()(ctx) {

  override lazy val millModuleDirectChildren: Seq[Module] =
    super.millModuleDirectChildren ++
      items.collect { case (_, v: mill.define.Module) => v }

  val items: List[(List[Any], T)] = for (c <- cases.toList) yield {
    val crossValues = (c match {case p: Product => p case v => Tuple1(v)}).productIterator.toList
    val relPath = ctx.segment.pathSegments
    val sub = ci.make(
      c,
      ctx
        .withSegments(ctx.segments ++ Seq(ctx.segment))
        .withMillSourcePath(ctx.millSourcePath / relPath)
        .withSegment(Segment.Cross(crossValues)),
      cases
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
