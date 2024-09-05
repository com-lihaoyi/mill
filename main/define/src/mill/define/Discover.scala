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

  inline def apply[T]: Discover = ${ Router.applyImpl[T] }

  private object Router {
    import quoted.*
    import mainargs.Macros.*
    import scala.util.control.NonFatal

    def applyImpl[T: Type](using Quotes): Expr[Discover] = {
      import quotes.reflect.*
      val seen = mutable.Set.empty[TypeRepr]
      val moduleSym = Symbol.requiredClass("mill.define.Module")
      val deprecatedSym = Symbol.requiredClass("scala.deprecated")
      def rec(tpe: TypeRepr): Unit = {
        if (seen.add(tpe)) {
          val typeSym = tpe.typeSymbol
          for {
            // for some reason mill.define.Foreign has NoSymbol as field member.
            m <- typeSym.fieldMembers.filterNot(_ == Symbol.noSymbol).toList.sortBy(_.name.toString)
            memberTpe = m.termRef
            if memberTpe.baseClasses.contains(moduleSym)
          } {
            rec(memberTpe)
            memberTpe.asType match {
              case '[mill.define.Cross[m]] =>
                rec(TypeRepr.of[m])
              case _ =>
                () // no cross argument to extract
            }
          }
        }
      }
      rec(TypeRepr.of[T])

      def methodReturn(tpe: TypeRepr): TypeRepr = tpe match
        case MethodType(_, _, res) => res
        case ByNameType(tpe) => tpe
        case _ => tpe

      def assertParamListCounts(
          curCls: TypeRepr,
          methods: Iterable[Symbol],
          cases: (TypeRepr, Int, String)*
      ): Unit = {
        for (m <- methods.toList) {
          cases
            .find { case (tt, n, label) =>
              val mType = curCls.memberType(m)
              val returnType = methodReturn(mType)
              returnType <:< tt && !(returnType <:< TypeRepr.of[Nothing])
            }
            .foreach { case (tt, n, label) =>
              if (m.paramSymss.length != n) report.errorAndAbort(
                s"$label definitions must have $n parameter list" + (if (n == 1) "" else "s"),
                m.pos.getOrElse(Position.ofMacroExpansion)
              )
            }
        }
      }

      def filterDefs(methods: List[Symbol]): List[Symbol] =
        methods.filterNot(m =>
          m.isSuperAccessor
          || m.hasAnnotation(deprecatedSym)
          || m.flags.is(Flags.Synthetic | Flags.Invisible | Flags.Private | Flags.Protected))

      // Make sure we sort the types and methods to keep the output deterministic;
      // otherwise the compiler likes to give us stuff in random orders, which
      // causes the code to be generated in random order resulting in code hashes
      // changing unnecessarily
      val mapping = for {
        discoveredModuleType <- seen.toSeq.sortBy(_.typeSymbol.fullName)
        curCls = discoveredModuleType
        methods = filterDefs(curCls.typeSymbol.methodMembers)
        declMethods = filterDefs(curCls.typeSymbol.declaredMethods)
        overridesRoutes = {
          assertParamListCounts(
            curCls,
            methods,
            (TypeRepr.of[mill.define.Command[?]], 1, "`Task.Command`"),
            (TypeRepr.of[mill.define.Target[?]], 0, "Target")
          )

          def sortedMethods(sub: TypeRepr, methods: Seq[Symbol] = methods): Seq[Symbol] =
            for {
              m <- methods.toList.sortBy(_.fullName)
              mType = curCls.memberType(m)
              returnType = methodReturn(mType)
              if returnType <:< sub
            } yield m

          Tuple3(
            for {
              m <- sortedMethods(sub = TypeRepr.of[mill.define.NamedTask[?]])
            } yield m.name,//.decoded // we don't need to decode the name in Scala 3
            for {
              m <- sortedMethods(sub = TypeRepr.of[mill.define.Command[?]])
            } yield curCls.asType match {
              case '[t] =>
                val expr =
                  try
                    createMainData[Any, t](
                      m,
                      m.annotations.find(_.tpe =:= TypeRepr.of[mainargs.main]).getOrElse('{new mainargs.main()}.asTerm),
                      m.paramSymss
                    ).asExprOf[mainargs.MainData[?, ?]]
                  catch {
                    case NonFatal(e) =>
                      val (before, Array(after, _*)) = e.getStackTrace().span(e => !(e.getClassName() == "mill.define.Discover$Router$" && e.getMethodName() == "applyImpl")): @unchecked
                      val trace = (before :+ after).map(_.toString).mkString("trace:\n", "\n", "\n...")
                      report.errorAndAbort(s"Error generating maindata for ${m.fullName}: ${e}\n$trace", m.pos.getOrElse(Position.ofMacroExpansion))
                  }
                // report.warning(s"generated maindata for ${m.fullName}:\n${expr.asTerm.show}", m.pos.getOrElse(Position.ofMacroExpansion))
                expr
            },
            for
              m <- sortedMethods(sub = TypeRepr.of[mill.define.Task[?]], methods = declMethods)
            yield m.name.toString
          )
        }
        if overridesRoutes._1.nonEmpty || overridesRoutes._2.nonEmpty
      } yield {
        val (names, mainDataExprs) = overridesRoutes
        val mainDatas = Expr.ofList(mainDataExprs)
        // by wrapping the `overridesRoutes` in a lambda function we kind of work around
        // the problem of generating a *huge* macro method body that finally exceeds the
        // JVM's maximum allowed method size
        val overridesLambda = '{
          def pair() = (${Expr(names)}, $mainDatas)
          pair()
        }
        val lhs = Ref(defn.Predef_classOf).appliedToType(discoveredModuleType.widen).asExprOf[Class[?]]
        '{$lhs -> $overridesLambda}
      }

      val expr: Expr[Discover] =
        '{
          // TODO: we can not import this here, so we have to import at the use site now, or redesign?
          // import mill.main.TokenReaders.*
          // import mill.api.JsonFormatters.*
          Discover.apply2(Map(${Varargs(mapping)}*))
        }
      // TODO: if needed for debugging, we can re-enable this
      // report.warning(s"generated discovery for ${TypeRepr.of[T].show}:\n${expr.asTerm.show}", TypeRepr.of[T].typeSymbol.pos.getOrElse(Position.ofMacroExpansion))
      expr
    }
  }
}
