package mill.define

import language.experimental.macros
import scala.collection.mutable
import scala.reflect.macros.blackbox

class Router(val ctx: blackbox.Context) extends mainargs.Macros(ctx) {
  import c.universe._

  def applyImpl[T: WeakTypeTag]: Expr[Discover[T]] = {
    val seen = mutable.Set.empty[Type]
    def rec(tpe: Type): Unit = {
      if (!seen(tpe)) {
        seen.add(tpe)
        for {
          m <- tpe.members
          memberTpe = m.typeSignature
          if memberTpe.resultType <:< typeOf[mill.define.Module] && memberTpe.paramLists.isEmpty
        } rec(memberTpe.resultType)

        if (tpe <:< typeOf[mill.define.Cross[_]]) {
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

    def assertParamListCounts(
        methods: Iterable[MethodSymbol],
        cases: (Type, Int, String)*
    ): Unit = {
      for (m <- methods.toList) {
        cases
          .find{case (tt, n, label) => m.returnType <:< tt}
          .foreach{case (tt, n, label) =>
            if (m.paramLists.length != n) c.abort(
              m.pos,
              s"$label definitions must have $n parameter list" + (if (n == 1) "" else "s")
            )
          }
      }
    }
    val mapping = for {
      discoveredModuleType <- seen
      curCls = discoveredModuleType
      methods = getValsOrMeths(curCls)
      overridesRoutes = {
        assertParamListCounts(
          methods,
          (weakTypeOf[mill.define.Command[_]], 1, "`T.command`"),
          (weakTypeOf[mill.define.Target[_]], 0, "Target"),
        )

        for {
          m <- methods.toList
          if m.returnType <:< weakTypeOf[mill.define.Command[_]]
        } yield (
          m.overrides.length,
          extractMethod(
            m.name,
            m.paramLists.flatten,
            m.pos,
            m.annotations.find(_.tree.tpe =:= typeOf[mainargs.main]),
            curCls,
            weakTypeOf[Any]
          )
        )

      }
      if overridesRoutes.nonEmpty
    } yield {
      // by wrapping the `overridesRoutes` in a lambda function we kind of work around
      // the problem of generating a *huge* macro method body that finally exceeds the
      // JVM's maximum allowed method size
      val overridesLambda = q"(() => $overridesRoutes)()"
      val lhs = q"classOf[${discoveredModuleType.typeSymbol.asClass}]"
      q"$lhs -> $overridesLambda"
    }

    c.Expr[Discover[T]](
      q"_root_.mill.define.Discover(_root_.scala.collection.immutable.Map(..$mapping))"
    )
  }
}
