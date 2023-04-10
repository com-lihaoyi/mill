package mill.define

import language.experimental.macros
import scala.reflect.ClassTag
import scala.reflect.macros.blackbox

object Cross {
  trait Module[V] extends mill.define.Module {
    def crossValue: V
  }
  case class Factory[T, -V](make: (V, mill.define.Ctx) => T)

  object Factory {
    implicit def make[T]: Factory[T, Any] = macro makeImpl[T]
    def makeImpl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[Factory[T, Any]] = {
      import c.universe._
      val tpe = weakTypeOf[T]

      if (tpe.typeSymbol.isClass) {
        if (tpe.typeSymbol.asClass.isTrait) {
          if (!(tpe <:< typeOf[Module[_]])) c.abort(
            c.enclosingPosition,
            s"Cross type $tpe must implement Cross.Module[T]"
          )
          val crossType = tpe.baseType(typeOf[Module[_]].typeSymbol).typeArgs.head
          val v1 = c.freshName(TermName("v1"))
          val ctx0 = c.freshName(TermName("ctx0"))
          val implicitCtx = c.freshName(TermName("implicitCtx"))
          val tree =
            q"""mill.define.Cross.Factory[$tpe, $crossType]{ ($v1: $crossType, $ctx0: ${tq""}) =>
            implicit val $implicitCtx = $ctx0
            new $tpe{ override def crossValue = $v1 }
          }.asInstanceOf[${weakTypeOf[Factory[T, Any]]}]"""

          c.Expr[Factory[T, Any]](tree)
        } else {
          val primaryConstructorArgs =
            tpe.typeSymbol.asClass.primaryConstructor.typeSignature.paramLists.head

          val oldArgStr = primaryConstructorArgs
            .map{ s => s"${s.name}: ${s.typeSignature}" }
            .mkString(", ")

          def parenWrap(s: String) =
            if (primaryConstructorArgs.size == 1) s
            else s"($s)"

          val newTypeStr = primaryConstructorArgs.map(_.typeSignature.toString).mkString(", ")
          val newForwarderStr = primaryConstructorArgs.map(_.name.toString).mkString(", ")

          c.abort(
            c.enclosingPosition,
            s"""Cross type $tpe must be trait, not a class. Please change:
               |
               |class Foo($oldArgStr)
               |
               |to:
               |
               |trait Foo extends Cross.Module[${parenWrap(newTypeStr)}]{
               |  val ${parenWrap(newForwarderStr)} = crossVersion
               |}
               |
               |Note that the `millSourcePath` of cross modules has changed in
               |Mill 0.11.0, an no longer includes the cross values by default.
               |If you have `def millSourcePath = super.millSourcePath / os.up`,
               |you may remove it. If you do not have this definition, you can
               |preserve the old behavior via `def millSourcePath = super.millSourcePath / crossValue`
               |
               |
               |""".stripMargin
          )
        }
      } else {
        c.abort(c.enclosingPosition, s"Cross type $tpe must be trait")
      }
    }
  }

  case class CrossSeq[+T](value: Seq[T])
  object CrossSeq {
    implicit def ofSingle[T](t: T): CrossSeq[T] = CrossSeq(Seq(t))
    implicit def ofMultiple[T](ts: Seq[T]): CrossSeq[T] = CrossSeq(ts)
  }

  trait Resolver[-T <: Cross.Module[_]] {
    def resolve[V <: T](c: Cross[V]): V
  }
}

/**
 * Models "cross-builds": sets of duplicate builds which differ only in the
 * value of one or more "case" variables whose values are determined at runtime.
 * Used via:
 *
 * object foo extends Cross[FooModule]("bar", "baz", "qux")
 * class FooModule extends Module{
 *   ... crossValue ...
 * }
 */
class Cross[M <: Cross.Module[_]](cases0: Cross.CrossSeq[T forSome { type T; type X >: M <: Cross.Module[T] }]*)(implicit
    ci: Cross.Factory[M, Any],
    ctx: mill.define.Ctx
) extends mill.define.Module()(ctx) {

  val cases = cases0.flatMap(_.value).toList
  override lazy val millModuleDirectChildren: Seq[Module] =
    super.millModuleDirectChildren ++
      items.collect { case (_, v: mill.define.Module) => v }

  val items: List[(List[Any], M)] = for (c <- cases) yield {
    val crossValues = (c match {
      case p: Product => p
      case v => Tuple1(v)
    }).productIterator.toList
    val relPath = ctx.segment.pathSegments
    val sub = ci.make(
      c,
      ctx
        .withSegments(ctx.segments ++ Seq(ctx.segment))
        .withMillSourcePath(ctx.millSourcePath / relPath)
        .withSegment(Segment.Cross(crossValues))
        .withCrossValues(cases)
    )
    (crossValues, sub)
  }
  val itemMap: Map[List[Any], M] = items.toMap

  /**
   * Fetch the cross module corresponding to the given cross values
   */
  def get(args: Seq[Any]): M = itemMap(args.toList)

  /**
   * Fetch the cross module corresponding to the given cross values
   */
  def apply(arg0: Any, args: Any*): M = itemMap(arg0 :: args.toList)

  /**
   * Fetch the relevant cross module given the implicit resolver you have in
   * scope. This is often the first cross module whose cross-version is
   * compatible with the current module.
   */
  def apply[V >: M <: Cross.Module[_]]()(implicit resolver: Cross.Resolver[V]): M = {
    resolver.resolve(this.asInstanceOf[Cross[V]]).asInstanceOf[M]
  }
}
