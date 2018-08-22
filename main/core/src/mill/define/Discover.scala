package mill.define
import mill.util.Router.EntryPoint

import language.experimental.macros
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

    def assertParamListCounts(methods: Iterable[router.c.universe.MethodSymbol],
                              cases: (c.Type, Int, String)*) = {
      for (m <- methods.toList){
        for ((tt, n, label) <- cases){
          if (m.returnType <:< tt.asInstanceOf[router.c.Type] &&
            m.paramLists.length != n){
            c.abort(
              m.pos.asInstanceOf[c.Position],
              s"$label definitions must have $n parameter list" + (if (n == 1) "" else "s")
            )
          }
        }
      }
    }
    val router = new mill.util.Router(c)
    val mapping = for{
      discoveredModuleType <- seen
      val curCls = discoveredModuleType.asInstanceOf[router.c.Type]
      val methods = router.getValsOrMeths(curCls)
      val overridesRoutes = {
        assertParamListCounts(
          methods,
          (weakTypeOf[mill.define.Sources], 0, "`T.sources`"),
          (weakTypeOf[mill.define.Input[_]], 0, "`T.input`"),
          (weakTypeOf[mill.define.Persistent[_]], 0, "`T.persistent`"),
          (weakTypeOf[mill.define.Target[_]], 0, "`T{...}`"),
          (weakTypeOf[mill.define.Command[_]], 1, "`T.command`")
        )

        for{
          m <- methods.toList
          if m.returnType <:< weakTypeOf[mill.define.Command[_]].asInstanceOf[router.c.Type]
        } yield (m.overrides.length, router.extractMethod(m, curCls).asInstanceOf[c.Tree])

      }
      if overridesRoutes.nonEmpty
    } yield {
      val lhs =  q"classOf[${discoveredModuleType.typeSymbol.asClass}]"
      val rhs = q"scala.Seq[(Int, mill.util.Router.EntryPoint[_])](..$overridesRoutes)"
      q"$lhs -> $rhs"
    }

    c.Expr[Discover[T]](q"mill.define.Discover(scala.collection.immutable.Map(..$mapping))")
  }
}
