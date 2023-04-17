package mill.define

import language.experimental.macros
import scala.reflect.macros.blackbox

object Cross {
  trait Module[T1] extends mill.define.Module {
    def crossValue: T1
    def crossValuesList: List[Any] = List(crossValue)
  }

  trait Module2[T1, T2] extends Module[T1] {
    def crossValue2: T2
    override def crossValuesList: List[Any] = List((crossValue, crossValue2))
  }

  trait Module3[T1, T2, T3] extends Module2[T1, T2] {
    def crossValue3: T3
    override def crossValuesList: List[Any] = List((crossValue, crossValue2, crossValue3))
  }

  trait Module4[T1, T2, T3, T4] extends Module3[T1, T2, T3]{
    def crossValue4: T4
    override def crossValuesList: List[Any] = List((crossValue, crossValue2, crossValue3, crossValue4))
  }

  trait Module5[T1, T2, T3, T4, T5] extends Module4[T1, T2, T3, T4] {
    def crossValue5: T5
    override def crossValuesList: List[Any] = List((crossValue, crossValue2, crossValue3, crossValue4, crossValue5))
  }

  case class Factory[T](makeList: Seq[mill.define.Ctx => T],
                        crossValuesListLists: Seq[Seq[Any]],
                        crossValuesRaw: Seq[Any])

  object Factory {
    /**
     * Implicitly constructs a Factory[M] for a target-typed `M`. Takes in an
     * expression of type `Any`, but type-checking on the macro- expanded code
     * provides some degree of type-safety.
     */
    implicit def make[M <: Module[_]](t: Any): Factory[M] = macro makeImpl[M]
    def makeImpl[T: c.WeakTypeTag](c: blackbox.Context)(t: c.Expr[Any]): c.Expr[Factory[T]] = {
      import c.universe._
      val tpe = weakTypeOf[T]

      if (!tpe.typeSymbol.isClass) {
        c.abort(c.enclosingPosition, s"Cross type $tpe must be trait")
      }

      if (!tpe.typeSymbol.asClass.isTrait) abortOldStyleClass(c)(tpe)

      val wrappedT = if (t.tree.tpe <:< typeOf[Seq[_]]) t.tree else q"_root_.scala.Seq($t)"
      val v1 = c.freshName(TermName("v1"))
      val ctx0 = c.freshName(TermName("ctx0"))
      val implicitCtx = c.freshName(TermName("implicitCtx"))

      val (newTree, valuesTree) =
        if (tpe <:< typeOf[Module5[_, _, _, _, _]])(
          q"""new $tpe{
            override def crossValue = $v1._1
            override def crossValue2 = $v1._2
            override def crossValue3 = $v1._3
            override def crossValue4 = $v1._4
            override def crossValue5 = $v1._5
          }""",
          q"$wrappedT.map(_.productIterator.toList)",
        )else if (tpe <:< typeOf[Module4[_, _, _, _]])(
          q"""new $tpe{
            override def crossValue = $v1._1
            override def crossValue2 = $v1._2
            override def crossValue3 = $v1._3
            override def crossValue4 = $v1._4
          }""",
          q"$wrappedT.map(_.productIterator.toList)"
        )else if (tpe <:< typeOf[Module3[_, _, _]])(
          q"""new $tpe{
            override def crossValue = $v1._1
            override def crossValue2 = $v1._2
            override def crossValue3 = $v1._3
          }""",
          q"$wrappedT.map(_.productIterator.toList)"
        )else if (tpe <:< typeOf[Module2[_, _]])(
          q"""new $tpe{
            override def crossValue = $v1._1
            override def crossValue2 = $v1._2
          }""", q"$wrappedT.map(_.productIterator.toList)"
        )else if (tpe <:< typeOf[Module[_]])(
          q"""new $tpe{
            override def crossValue = $v1
          }""",
          q"$wrappedT.map(List(_))"
        )else c.abort(
          c.enclosingPosition,
          s"Cross type $tpe must implement Cross.Module[T]"
        )

      val tree = q"""
        mill.define.Cross.Factory[$tpe](
          makeList = $wrappedT.map(($v1: ${tq""}) =>
            ($ctx0: ${tq""}) => {
              implicit val $implicitCtx = $ctx0
              $newTree
            }
          ),
          crossValuesListLists = $valuesTree,
          crossValuesRaw = $wrappedT
       ).asInstanceOf[${weakTypeOf[Factory[T]]}]
      """

      c.Expr[Factory[T]](tree)
    }

    def abortOldStyleClass(c: blackbox.Context)(tpe: c.Type) = {
      val primaryConstructorArgs =
        tpe.typeSymbol.asClass.primaryConstructor.typeSignature.paramLists.head

      val oldArgStr = primaryConstructorArgs
        .map { s => s"${s.name}: ${s.typeSignature}" }
        .mkString(", ")

      def parenWrap(s: String) =
        if (primaryConstructorArgs.size == 1) s
        else s"($s)"

      val newTypeStr = primaryConstructorArgs.map(_.typeSignature.toString).mkString(", ")
      val newForwarderStr = primaryConstructorArgs.map(_.name.toString).mkString(", ")

      c.abort(
        c.enclosingPosition,
        s"""
           |Cross type ${tpe.typeSymbol.name} must be trait, not a class. Please change:
           |
           |  class ${tpe.typeSymbol.name}($oldArgStr)
           |
           |To:
           |
           |  trait ${tpe.typeSymbol.name} extends Cross.Module[${parenWrap(newTypeStr)}]{
           |    val ${parenWrap(newForwarderStr)} = crossValue
           |  }
           |
           |You also no longer use `: _*` when instantiating a cross-module:
           |
           |  Cross[${tpe.typeSymbol.name}](values:_*)
           |
           |Instead, you can pass the sequence directly:
           |
           |  Cross[${tpe.typeSymbol.name}](values)
           |
           |Note that the `millSourcePath` of cross modules has changed in
           |Mill 0.11.0, and no longer includes the cross values by default.
           |If you have `def millSourcePath = super.millSourcePath / os.up`,
           |you may remove it. If you do not have this definition, you can
           |preserve the old behavior via `def millSourcePath = super.millSourcePath / crossValue`
           |
           |""".stripMargin
      )
    }
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
class Cross[M <: Cross.Module[_]](factories: Cross.Factory[M]*)
                                 (implicit ctx: mill.define.Ctx)
  extends mill.define.Module()(ctx) {

  override lazy val millModuleDirectChildren: Seq[Module] =
    super.millModuleDirectChildren ++
    items.collect { case (_, v: mill.define.Module) => v }

  val items: List[(List[Any], M)] = for {
    factory <- factories.toList
    (crossValues, make) <- factory.crossValuesListLists.zip(factory.makeList)
  } yield {
    val relPath = ctx.segment.pathSegments
    val sub = make(
      ctx
        .withSegments(ctx.segments ++ Seq(ctx.segment))
        .withMillSourcePath(ctx.millSourcePath / relPath)
        .withSegment(Segment.Cross(crossValues))
        .withCrossValues(factories.flatMap(_.crossValuesRaw))
    )

    (crossValues.toList, sub)
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
