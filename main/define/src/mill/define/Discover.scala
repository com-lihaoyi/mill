package mill.define

import scala.collection.mutable

/**
 * Macro to walk the module tree and generate `mainargs` entrypoints for any
 * `Task.Command` methods that it finds.
 *
 * Note that unlike the rest of Mill's module-handling logic which uses Java
 * reflection, generation of entrypoints requires typeclass resolution, and so
 * needs to be done at compile time. Thus, we walk the entire module tree,
 * collecting all the module `Class[_]`s we can find, and for each one generate
 * the `mainargs.MainData` containing metadata and resolved typeclasses for all
 * the `Task.Command` methods we find. This mapping from `Class[_]` to `MainData`
 * can then be used later to look up the `MainData` for any module.
 */
class Discover(val classInfo: Map[Class[_], Discover.ClassInfo])

object Discover {
  class ClassInfo(
      val entryPoints: Seq[mainargs.MainData[_, _]],
      val declaredNames: Seq[String]
  )

  inline def apply[T]: Discover = ${ Router.applyImpl[T] }

  private object Router {
    import quoted.*
    import mainargs.Macros.*
    import scala.util.control.NonFatal

    def applyImpl[T: Type](using quotes: Quotes): Expr[Discover] = {
      import quotes.reflect.*
      val seen = mutable.Set.empty[TypeRepr]
      val moduleSym = Symbol.requiredClass("mill.define.Module")
      val deprecatedSym = Symbol.requiredClass("scala.deprecated")
      def rec(tpe: TypeRepr): Unit = {
        if (seen.add(tpe)) {
          val typeSym = tpe.typeSymbol
          val memberTypes: Seq[TypeRepr] = for {
            m <- typeSym.fieldMembers ++ typeSym.methodMembers
            if m != Symbol.noSymbol
          } yield m.termRef

          val parentTypes: Seq[TypeRepr] = tpe.baseClasses.map(_.typeRef)

          for {
            tpe <- memberTypes ++ parentTypes
            if tpe.baseClasses.contains(moduleSym)
          } {
            rec(tpe)
            tpe.asType match {
              case '[mill.define.Cross[m]] => rec(TypeRepr.of[m])
              case _ => () // no cross argument to extract
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
            || m.flags.is(
              Flags.Synthetic | Flags.Invisible | Flags.Private | Flags.Protected
            )
        )

      def sortedMethods(curCls: TypeRepr, sub: TypeRepr, methods: Seq[Symbol]): Seq[Symbol] =
        for {
          m <- methods.toList.sortBy(_.fullName)
          mType = curCls.memberType(m)
          returnType = methodReturn(mType)
          if returnType <:< sub
        } yield m

      // Make sure we sort the types and methods to keep the output deterministic;
      // otherwise the compiler likes to give us stuff in random orders, which
      // causes the code to be generated in random order resulting in code hashes
      // changing unnecessarily
      val mapping: Seq[(TypeRepr, (Seq[scala.quoted.Expr[mainargs.MainData[?, ?]]], Seq[String]))] =
        for {
          curCls <- seen.toSeq.sortBy(_.typeSymbol.fullName)
        } yield {
          val declMethods = filterDefs(curCls.typeSymbol.declaredMethods)
          assertParamListCounts(
            curCls,
            declMethods,
            (TypeRepr.of[mill.define.Command[?]], 1, "`Task.Command`"),
            (TypeRepr.of[mill.define.Target[?]], 0, "Target")
          )

          val names =
            sortedMethods(
              curCls,
              sub = TypeRepr.of[mill.define.NamedTask[?]],
              declMethods
            ).map(_.name)
          val entryPoints = for {
            m <- sortedMethods(curCls, sub = TypeRepr.of[mill.define.Command[?]], declMethods)
          } yield curCls.asType match {
            case '[t] =>
              val expr =
                try
                  createMainData[Any, t](
                    m,
                    m.annotations
                      .find(_.tpe =:= TypeRepr.of[mainargs.main])
                      .getOrElse('{ new mainargs.main() }.asTerm),
                    m.paramSymss
                  ).asExprOf[mainargs.MainData[?, ?]]
                catch {
                  case NonFatal(e) =>
                    report.errorAndAbort(
                      s"Error generating maindata for ${m.fullName}: ${e}\n${e.getStackTrace().mkString("\n")}",
                      m.pos.getOrElse(Position.ofMacroExpansion)
                    )
                }
              expr
          }

          (curCls.widen, (entryPoints, names))
        }

      val mappingExpr = mapping.collect {
        case (cls, (entryPoints, names)) if entryPoints.nonEmpty || names.nonEmpty =>
          // by wrapping the `overridesRoutes` in a lambda function we kind of work around
          // the problem of generating a *huge* macro method body that finally exceeds the
          // JVM's maximum allowed method size
          '{
            def func() = new ClassInfo(${ Expr.ofList(entryPoints.toList) }, ${ Expr(names) })

            (${ Ref(defn.Predef_classOf).appliedToType(cls).asExprOf[Class[?]] }, func())
          }
      }

      '{ new Discover(Map[Class[_], ClassInfo](${ Varargs(mappingExpr) }*)) }
    }
  }
}
