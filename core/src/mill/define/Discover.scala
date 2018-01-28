package mill.define
import language.experimental.macros
import ammonite.main.Router.{EntryPoint, Overrides}
import sourcecode.Compat.Context

import scala.collection.mutable
import scala.reflect.macros.blackbox



case class Discover[T](value: Map[Class[_], Seq[(Int, EntryPoint[_])]])
object Discover {
  def apply[T]: Discover[T] = macro applyImpl[T]

  def applyImpl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[Discover[T]] = {
    import c.universe._
    import compat._
    val seen = mutable.Set.empty[Type]
    def rec(tpe: Type): Unit = {
      if (!seen(tpe)){
        seen.add(tpe)
        for{
          m <- tpe.members
          memberTpe = m.typeSignature
          if memberTpe.resultType <:< typeOf[mill.define.Module] && memberTpe.paramLists.isEmpty
        } rec(memberTpe.resultType)

        if (tpe <:< typeOf[mill.define.Cross[_]]){
          val inner = typeOf[Cross[_]]
            .typeSymbol
            .asClass
            .typeParams
            .head
            .asType
            .toType
            .asSeenFrom(tpe, typeOf[Cross[_]].typeSymbol)

          rec(inner)
        }
      }
    }
    rec(weakTypeOf[T])

    val router = new ammonite.main.Router(c)
    val mapping = for{
      discoveredModuleType <- seen
      val curCls = discoveredModuleType.asInstanceOf[router.c.Type]
      val methods = router.getValsOrMeths(curCls)
      val overridesRoutes = {
        for{
          m <- methods.toList
          if m.returnType <:< weakTypeOf[mill.define.Command[_]].asInstanceOf[router.c.Type]
        } yield (m.overrides.length, router.extractMethod(m, curCls).asInstanceOf[c.Tree])
      }
      if overridesRoutes.nonEmpty
      val (overrides, routes) = overridesRoutes.unzip

    } yield {
      val lhs =  q"classOf[${discoveredModuleType.typeSymbol.asClass}]"
      val rhs = q"scala.Seq[(Int, ammonite.main.Router.EntryPoint[${discoveredModuleType.typeSymbol.asClass}])](..$overridesRoutes)"
      q"$lhs -> $rhs"
    }

    c.Expr[Discover[T]](q"mill.define.Discover(scala.collection.immutable.Map(..$mapping))")
  }
}
