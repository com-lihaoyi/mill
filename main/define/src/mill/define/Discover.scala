package mill.define

import scala.collection.mutable
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

/**
 * Macro to walk the module tree and generate `mainargs` entrypoints for any
 * `Task.Command` methods that it finds.
 *
 * Note that unlike the rest of Mill's module-handling logic which uses Java
 * reflection, generation of entrypoints requires typeclass resolution, and so
 * needs to be done at compile time. Thus we walk the entire module tree,
 * collecting all the module `Class[_]`s we can find, and for each one generate
 * the `mainargs.MainData` containing metadata and resolved typeclasses for all
 * the `Task.Command` methods we find. This mapping from `Class[_]` to `MainData`
 * can then be used later to look up the `MainData` for any module.
 */
case class Discover private (
    value: Map[
      Class[_],
      (Seq[String], Seq[mainargs.MainData[_, _]], Seq[String])
    ],
    dummy: Int = 0 /* avoid conflict with Discover.apply(value: Map) below*/
) {
  @deprecated("Binary compatibility shim", "Mill 0.11.4")
  private[define] def this(value: Map[Class[_], Seq[mainargs.MainData[_, _]]]) =
    this(value.view.mapValues((Nil, _, Nil)).toMap)
  // Explicit copy, as we also need to provide an override for bin-compat reasons
  def copy[T](
      value: Map[
        Class[_],
        (Seq[String], Seq[mainargs.MainData[_, _]], Seq[String])
      ] = value,
      dummy: Int = dummy /* avoid conflict with Discover.apply(value: Map) below*/
  ): Discover = new Discover(value, dummy)
  @deprecated("Binary compatibility shim", "Mill 0.11.4")
  private[define] def copy(value: Map[Class[_], Seq[mainargs.MainData[_, _]]]): Discover = {
    new Discover(value.view.mapValues((Nil, _, Nil)).toMap, dummy)
  }
}

object Discover {
  def apply2[T](value: Map[Class[_], (Seq[String], Seq[mainargs.MainData[_, _]], Seq[String])])
      : Discover =
    new Discover(value)

  @deprecated("Binary compatibility shim", "Mill 0.11.4")
  def apply[T](value: Map[Class[_], Seq[mainargs.MainData[_, _]]]): Discover =
    new Discover(value.view.mapValues((Nil, _, Nil)).toMap)

  def apply[T]: Discover = macro Router.applyImpl[T]

  private class Router(val ctx: blackbox.Context) extends mainargs.Macros(ctx) {
    import c.universe._

    def applyImpl[T: WeakTypeTag]: Expr[Discover] = {
      val seen = mutable.Set.empty[Type]
      def rec(tpe: Type): Unit = {
        if (!seen(tpe)) {
          seen.add(tpe)
          for {
            m <- tpe.members.toList.sortBy(_.name.toString)
            if !m.isType
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
        declMethods = curCls.decls.toList.collect {
          case m: MethodSymbol if !m.isSynthetic && m.isPublic => m
        }
        overridesRoutes = {
          assertParamListCounts(
            methods,
            (weakTypeOf[mill.define.Command[_]], 1, "`Task.Command`"),
            (weakTypeOf[mill.define.Target[_]], 0, "Target")
          )

          Tuple3(
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
            ),
            for {
              m <- declMethods.sortBy(_.fullName)
              if m.returnType <:< weakTypeOf[mill.define.Task[_]]
            } yield m.name.decodedName.toString
          )
        }
        if overridesRoutes._1.nonEmpty || overridesRoutes._2.nonEmpty || overridesRoutes._3.nonEmpty
      } yield {
        val lhs = q"classOf[${discoveredModuleType.typeSymbol.asClass.toType}]"

        // by wrapping the `overridesRoutes` in a lambda function we kind of work around
        // the problem of generating a *huge* macro method body that finally exceeds the
        // JVM's maximum allowed method size
        val overridesLambda = q"(() => $overridesRoutes)()"
        q"$lhs -> $overridesLambda"
      }

      c.Expr[Discover](
        q"import mill.api.JsonFormatters._; _root_.mill.define.Discover.apply2(_root_.scala.collection.immutable.Map(..$mapping))"
      )
    }
  }
}
