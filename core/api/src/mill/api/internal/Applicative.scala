package mill.api.internal

import mill.api.Task
import mill.api.daemon.internal.internal
import scala.annotation.compileTimeOnly

import scala.quoted.*

/**
 * A generic Applicative-functor macro: translates calls to
 *
 * Applier.apply{ ... applyable1.apply() ... applyable2.apply() ... }
 *
 * into
 *
 * Applier.zipMap(applyable1, applyable2){ (a1, a2, ctx) => ... a1 ... a2 ... }
 */
@internal
object Applicative {

  trait Applyable[M[+_], +T] { this: M[T] =>
    @compileTimeOnly("Task#apply() can only be used within a Task{...} block")
    def apply(): T = ???
  }

  type Id[+T] = T

  /**
   * @param allowNestedTasks whether `Task[Task[A]]` or `Task[Something[Task[A]]]` and similar structures
   *                         are allowed.
   */
  private[mill] def impl[M[_]: Type, W[_]: Type, Z[_]: Type, T: Type, Ctx: Type](using
      Quotes
  )(
      traverseCtx: (Expr[Seq[W[Any]]], Expr[(Seq[Any], Ctx) => Z[T]]) => Expr[M[T]],
      t: Expr[Z[T]],
      allowTaskReferences: Boolean = true,
      allowNestedTasks: Boolean = false
  ): Expr[M[T]] = {
    import quotes.reflect.*

    def checkForNestedTasks() = {
      val taskType = TypeRepr.of[Task[?]]

      def isTask(tpr: TypeRepr): Boolean =
        // We use `typeSymbol` for a robust check.
        tpr.typeSymbol == taskType.typeSymbol

      def containsTask(tpr: TypeRepr): Boolean = {
        // Dealias to handle type aliases like `type MyTask[A] = Task[A]`
        val currentType = tpr.dealias

        if (isTask(currentType)) true
        else
          // Otherwise, check all of its type arguments recursively.
          // e.g., for Map[Int, String], this would be [Int, String]
          currentType.typeArgs.exists(containsTask)
      }

      val resultTypeRepr = TypeRepr.of[M[T]].dealias

      if (isTask(resultTypeRepr)) {

        /** The `A` to the `Task[A]`. */
        val innerTypeRepr = resultTypeRepr.typeArgs match {
          case innerTypeRepr :: Nil => innerTypeRepr
          case other => throw new IllegalStateException(
              s"Task[_] should have exactly one type parameter, but got ${other.map(_.show)}. " +
                "This is a bug in mill."
            )
        }

        if (containsTask(innerTypeRepr)) {
          report.errorAndAbort(
            // report.warning(
            s"""|A `Task[A]` cannot be a parameter of another `Task[A]` because the inner task would not
                |be executed.
                |
                |See https://github.com/com-lihaoyi/mill/issues/5263 for more information.
                |
                |Type of the result: ${resultTypeRepr.show}
                |""".stripMargin,
            Position.ofMacroExpansion
          )
        }
      }
    }

    if (!allowNestedTasks) checkForNestedTasks()

    val targetApplySym = TypeRepr.of[Applyable[Nothing, ?]].typeSymbol.methodMember("apply").head

    // Derived from @olafurpg's
    // https://gist.github.com/olafurpg/596d62f87bf3360a29488b725fbc7608

    def extractDefs(tree: Tree): Set[Symbol] =
      new TreeAccumulator[Set[Symbol]] {
        override def foldTree(x: Set[Symbol], tree: Tree)(owner: Symbol): Set[Symbol] = tree match
          case tree: Definition => foldOverTree(x + tree.symbol, tree)(owner)
          case tree => foldOverTree(x, tree)(owner)
      }.foldTree(Set.empty, tree)(Symbol.spliceOwner)

    def visitAllTrees(tree: Tree)(f: Tree => Unit): Unit =
      new TreeTraverser {
        override def traverseTree(tree: Tree)(owner: Symbol): Unit =
          f(tree)
          traverseTreeChildren(tree)(owner)
      }.traverseTree(tree)(Symbol.spliceOwner)

    val defs = extractDefs(t.asTerm)

    var hasErrors = false

    def macroError(msg: String, pos: Position): Unit = {
      hasErrors = true
      report.error(msg, pos)
    }

    def transformed(
        itemsRef: Expr[Seq[Any]],
        ctxRef: Expr[Ctx]
    ): (Expr[Z[T]], Expr[Seq[W[Any]]]) = {
      val exprs = collection.mutable.Buffer.empty[Tree]
      val treeMap = new TreeMap {

        override def transformTerm(tree: Term)(owner: Symbol): Term = tree match
          case t @ Apply(sel @ Select(fun, "apply"), Nil)
              if sel.symbol == targetApplySym && allowTaskReferences =>
            val localDefs = extractDefs(fun)
            visitAllTrees(t) { x =>
              val sym = x.symbol
              if (sym != Symbol.noSymbol && defs(sym) && !localDefs(sym)) {
                macroError(
                  "Task#apply() call cannot use `" + x.symbol + "` defined within the Task{...} block",
                  x.pos
                )
              }
            }

            t.tpe.asType match
              case '[tt] =>
                exprs += fun
                '{ $itemsRef(${ Expr(exprs.size - 1) }).asInstanceOf[tt] }.asTerm
          case t
              if t.symbol.exists
                && t.symbol.annotations.exists(
                  _.tpe =:= TypeRepr.of[mill.api.TaskCtx.ImplicitStub]
                ) =>
            ctxRef.asTerm

          case t => super.transformTerm(t)(owner)
        end transformTerm
      }

      val newBody = treeMap.transformTree(t.asTerm)(Symbol.spliceOwner).asExprOf[Z[T]]
      val exprsList = Expr.ofList(exprs.toList.map(_.asExprOf[W[Any]]))

      (newBody, exprsList)
    }

    val (callback, exprsList) = {
      var exprsExpr: Expr[Seq[W[Any]]] | Null = null
      val cb = '{ (items: Seq[Any], ctx: Ctx) =>
        ${
          val (body, exprs) = transformed('items, 'ctx)
          exprsExpr = exprs
          body
        }
      }
      (cb, exprsExpr.nn)
    }

    if hasErrors then
      '{ throw new RuntimeException("stub implementation - macro expansion had errors") }
    else
      traverseCtx(exprsList, callback)
  }
}
