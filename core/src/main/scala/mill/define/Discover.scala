package mill.define
import language.experimental.macros
import ammonite.main.Router.EntryPoint

import scala.collection.mutable
import scala.reflect.macros.blackbox

case class Discover(value: Map[Class[_], Seq[EntryPoint[_]]])
object Discover {
  def apply[T]: Discover = macro applyImpl[T]

  def applyImpl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[Discover] = {
    import c.universe._
    val seen = mutable.Set.empty[Type]
    def rec(tpe: Type): Unit = {
      println("Rec! " + tpe)
      if (!seen(tpe)){
        seen.add(tpe)
        for{
          m <- tpe.members
          memberTpe = m.typeSignature
          if memberTpe.resultType <:< typeOf[mill.define.Module] && memberTpe.paramLists.isEmpty
        } rec(memberTpe.resultType)
      }
    }
    rec(weakTypeOf[T])

    val router = new ammonite.main.Router(c)
    val mapping = for{
      discoveredModuleType <- seen
      val routes = router.getAllRoutesForClass(
        discoveredModuleType.asInstanceOf[router.c.Type],
        _.returnType <:< weakTypeOf[mill.define.Command[_]].asInstanceOf[router.c.Type]
      ).map(_.asInstanceOf[c.Tree])
      if routes.nonEmpty
    } yield {
      val lhs =  q"classOf[${discoveredModuleType.typeSymbol.asClass}]"
      val rhs = q"scala.Seq[ammonite.main.Router.EntryPoint[${discoveredModuleType.typeSymbol.asClass}]](..$routes)"
      q"$lhs -> $rhs"
    }

    c.Expr[Discover](q"mill.define.Discover(scala.collection.immutable.Map(..$mapping))")
  }
}
