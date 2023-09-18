package mill.define

import language.experimental.macros
import scala.collection.mutable
import scala.reflect.macros.blackbox

/**
 * Macro to walk the module tree and generate `mainargs` entrypoints for any
 * `T.command` methods that it finds.
 *
 * Note that unlike the rest of Mill's module-handling logic which uses Java
 * reflection, generation of entrypoints requires typeclass resolution, and so
 * needs to be done at compile time. Thus we walk the entire module tree,
 * collecting all the module `Class[_]`s we can find, and for each one generate
 * the `mainargs.MainData` containing metadata and resolved typeclasses for all
 * the `T.command` methods we find. This mapping from `Class[_]` to `MainData`
 * can then be used later to look up the `MainData` for any module.
 */
case class Discover[T] private (
    val value: Map[
      Class[_],
      (Seq[String], Seq[mainargs.MainData[_, _]])
    ],
    dummy: Int = 0 /* avoid conflict with Discover.apply(value: Map) below*/
) {
  @deprecated("Binary compatibility shim", "Mill 0.11.4")
  def this(value: Map[Class[_], Seq[mainargs.MainData[_, _]]]) =
    this(value.view.mapValues((Nil, _)).toMap)
}

object Discover {
  def apply2[T](value: Map[Class[_], (Seq[String], Seq[mainargs.MainData[_, _]])]): Discover[T] =
    new Discover[T](value)

  @deprecated("Binary compatibility shim", "Mill 0.11.4")
  def apply[T](value: Map[Class[_], Seq[mainargs.MainData[_, _]]]): Discover[T] =
    new Discover[T](value.view.mapValues((Nil, _)).toMap)

  def apply[T]: Discover[T] = macro Router.applyImpl[T]

  private class Router(val ctx: blackbox.Context) extends mainargs.Macros(ctx) {
    import c.universe._

    def applyImpl[T: WeakTypeTag]: Expr[Discover[T]] = {
      val seen = mutable.Set.empty[Type]
      def rec(tpe: Type): Unit = {
        if (!seen(tpe)) {
          seen.add(tpe)
          for {
            m <- tpe.members.toList.sortBy(_.name.toString)
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
            .find { case (tt, n, label) =>
              m.returnType <:< tt && !(m.returnType <:< weakTypeOf[Nothing])
            }
            .foreach { case (tt, n, label) =>
              if (m.paramLists.length != n) c.abort(
                m.pos,
                s"$label definitions must have $n parameter list" + (if (n == 1) "" else "s")
              )
            }
        }
      }

      // Make sure we sort the types and methods to keep the output deterministic;
      // otherwise the compiler likes to give us stuff in random orders, which
      // causes the code to be generated in random order resulting in code hashes
      // changing unnecessarily
      val mapping = for {
        discoveredModuleType <- seen.toSeq.sortBy(_.typeSymbol.fullName)
        curCls = discoveredModuleType
        methods = getValsOrMeths(curCls)
        overridesRoutes = {
          assertParamListCounts(
            methods,
            (weakTypeOf[mill.define.Command[_]], 1, "`T.command`"),
            (weakTypeOf[mill.define.Target[_]], 0, "Target")
          )

          Tuple2(
            for {
              m <- methods.toList.sortBy(_.fullName)
              if m.returnType <:< weakTypeOf[mill.define.NamedTask[_]]
            } yield m.name.decoded,
            for {
              m <- methods.toList.sortBy(_.fullName)
              if m.returnType <:< weakTypeOf[mill.define.Command[_]]
            } yield extractMethod(
              m.name,
              m.paramLists.flatten,
              m.pos,
              m.annotations.find(_.tree.tpe =:= typeOf[mainargs.main]),
              curCls,
              weakTypeOf[Any]
            )
          )
        }
        if overridesRoutes._1.nonEmpty || overridesRoutes._2.nonEmpty
      } yield {
        // by wrapping the `overridesRoutes` in a lambda function we kind of work around
        // the problem of generating a *huge* macro method body that finally exceeds the
        // JVM's maximum allowed method size
        val overridesLambda = q"(() => $overridesRoutes)()"
        val lhs = q"classOf[${discoveredModuleType.typeSymbol.asClass}]"
        q"$lhs -> $overridesLambda"
      }

      c.Expr[Discover[T]](
        q"import _root_.mill.main.TokenReaders._; _root_.mill.define.Discover.apply2(_root_.scala.collection.immutable.Map(..$mapping))"
      )
    }
  }
}
