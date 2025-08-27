package mill.api

import scala.collection.mutable

/**
 * Macro to walk the module tree and generate `mainargs` entrypoints for any
 * `Task.Command` methods that it finds. Needs to be provided for every
 * [[ExternalModule]] that you define.
 *
 * Note that unlike the rest of Mill's module-handling logic which uses Java
 * reflection, generation of entrypoints requires typeclass resolution, and so
 * needs to be done at compile time. Thus, we walk the entire module tree,
 * collecting all the module `Class[_]`s we can find, and for each one generate
 * the `mainargs.MainData` containing metadata and resolved typeclasses for all
 * the `Task.Command` methods we find. This mapping from `Class[_]` to `MainData`
 * can then be used later to look up the `MainData` for any module.
 */
final class Discover(val classInfo: Map[Class[?], Discover.ClassInfo]) {
  private[mill] def resolveEntrypoint(cls: Class[?], name: String) = {
    val res = for {
      (cls2, node) <- classInfo
      if cls2.isAssignableFrom(cls)
      ep <- node.entryPoints
      if ep.mainName.getOrElse(ep.defaultName) == name
    } yield ep

    // When there are multiple entrypoints matching the class and name,
    // return the one with the longest argument lists, since users may
    // add additional arguments to a command (with default values) to
    // preserve binary compatibility while evolving the method signature
    res.maxByOption(_.argSigs0.length)
  }
}

object Discover {
  final class ClassInfo(
      val entryPoints: Seq[mainargs.MainData[?, ?]],
      val declaredTasks: Seq[TaskInfo]
  ) {
    lazy val declaredTaskNameSet = declaredTasks.map(_.name).toSet
  }
  final class TaskInfo(val name: String)

  inline def apply[T]: Discover = ${ Router.applyImpl[T] }

  private object Router {
    import quoted.*
    import mainargs.Macros.*
    import scala.util.control.NonFatal

    def applyImpl[T: Type](using quotes: Quotes): Expr[Discover] = {
      import quotes.reflect.*
      val seen = mutable.Set.empty[TypeRepr]
      val moduleSym = Symbol.requiredClass("mill.api.Module")
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
              case '[mill.api.Cross[m]] => rec(TypeRepr.of[m])
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

      def filterDefs(methods: List[Symbol]): List[Symbol] =
        methods.filterNot { m =>
          m.isSuperAccessor
          || m.hasAnnotation(deprecatedSym)
          || m.flags.is(
            Flags.Synthetic | Flags.Invisible | Flags.Private | Flags.Protected
          )
          || m.privateWithin.nonEmpty // for some reason `Flags.Private` doesn't always work
        }

      def sortedMethods(curCls: TypeRepr, sub: TypeRepr, methods: Seq[Symbol]): Seq[Symbol] =
        for {
          m <- methods.toList.sortBy(_.fullName)
          if !m.flags.is(Flags.Deferred) // Abstract methods
          mType = curCls.memberType(m)
          returnType = methodReturn(mType)
          if returnType <:< sub
        } yield m

      // Make sure we sort the types and methods to keep the output deterministic;
      // otherwise the compiler likes to give us stuff in random orders, which
      // causes the code to be generated in random order resulting in code hashes
      // changing unnecessarily
      val mapping: Seq[(TypeRepr, (Seq[scala.quoted.Expr[mainargs.MainData[?, ?]]], Seq[String]))] =
        for (curCls <- seen.toSeq.sortBy(_.typeSymbol.fullName)) yield {
          val declMethods = filterDefs(curCls.typeSymbol.declaredMethods)

          val names =
            sortedMethods(
              curCls,
              sub = TypeRepr.of[mill.api.Task.Named[?]],
              declMethods
            ).map(_.name)
          val entryPoints = for {
            m <- sortedMethods(curCls, sub = TypeRepr.of[Task.Command[?]], declMethods)
            if m.paramSymss.length == 1
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

      def classOf(cls: TypeRepr) = Ref(defn.Predef_classOf).appliedToType(cls).asExprOf[Class[?]]
      val mappingExpr = mapping.collect {
        case (cls, (entryPoints, names)) if entryPoints.nonEmpty || names.nonEmpty =>
          // by wrapping the `overridesRoutes` in a lambda function we kind of work around
          // the problem of generating a *huge* macro method body that finally exceeds the
          // JVM's maximum allowed method size
          '{
            def func() = new ClassInfo(
              ${ Expr.ofList(entryPoints.toList) },
              ${ Expr.ofList(names.map(s => '{ new TaskInfo(${ Expr(s) }) })) }
            )

            (${ classOf(cls) }, func())
          }
      }

      '{ new Discover(Map[Class[?], ClassInfo](${ Varargs(mappingExpr) }*)) }
    }
  }
}
