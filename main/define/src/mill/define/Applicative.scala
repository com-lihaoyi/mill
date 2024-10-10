package mill.define

import mill.api.internal

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
  trait ApplyHandler[M[+_]] {

    /**
     * Extracts the current value [[T]] out of the wrapping [[M[T]]
     */
    def apply[T](t: M[T]): T
  }
  object ApplyHandler {
    @compileTimeOnly("Target#apply() can only be used with a Task{...} block")
    implicit def defaultApplyHandler[M[+_]]: ApplyHandler[M] = ???
  }
  trait Applyable[M[+_], +T] {
    def self: M[T]
    def apply()(implicit handler: ApplyHandler[M]): T = handler(self)
  }

  type Id[+T] = T

  trait Applyer[W[_], T[_], Z[_], Ctx] {
    def ctx()(implicit c: Ctx): Ctx = c
    def traverseCtx[I, R](xs: Seq[W[I]])(f: (IndexedSeq[I], Ctx) => Z[R]): T[R]
  }

  def impl[M[_]: Type, W[_]: Type, Z[_]: Type, T: Type, Ctx: Type](using
      Quotes
  )(traverseCtx: (Expr[Seq[W[Any]]], Expr[(IndexedSeq[Any], Ctx) => Z[T]]) => Expr[M[T]], t: Expr[Z[T]]): Expr[M[T]] = {
    import quotes.reflect.*

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
        itemsRef: Expr[IndexedSeq[Any]],
        ctxRef: Expr[Ctx]
    ): (Expr[Z[T]], Expr[Seq[W[Any]]]) = {
      val exprs = collection.mutable.Buffer.empty[Tree]
      val treeMap = new TreeMap {

        override def transformTerm(tree: Term)(owner: Symbol): Term = tree match
          // case t @ '{$fun.apply()($handler)}
          case t @ Apply(Apply(sel @ Select(fun, "apply"), Nil), List(handler))
              if sel.symbol == targetApplySym =>
            val localDefs = extractDefs(fun)
            visitAllTrees(t) { x =>
              val sym = x.symbol
              if (sym != Symbol.noSymbol && defs(sym) && !localDefs(sym)) {
                macroError(
                  "Target#apply() call cannot use `" + x.symbol + "` defined within the Task{...} block",
                  x.pos
                )
              }
            }

            t.tpe.asType match
              case '[tt] =>
                // val tempName = c.freshName(TermName("tmp"))
                // val tempSym = c.internal.newTermSymbol(c.internal.enclosingOwner, tempName)
                // c.internal.setInfo(tempSym, t.tpe)
                // val tempIdent = Ident(tempSym)
                // c.internal.setType(tempIdent, t.tpe)
                // c.internal.setFlag(tempSym, (1L << 44).asInstanceOf[c.universe.FlagSet])
                // val itemsIdent = Ident(itemsSym)
                // exprs.append(q"$fun")
                exprs += fun
                '{ $itemsRef(${ Expr(exprs.size - 1) }).asInstanceOf[tt] }.asTerm
          case t
              if t.symbol.exists
                && t.symbol.annotations.exists(_.tpe =:= TypeRepr.of[mill.api.Ctx.ImplicitStub]) =>
            // val tempIdent = Ident(ctxSym)
            // c.internal.setType(tempIdent, t.tpe)
            // c.internal.setFlag(ctxSym, (1L << 44).asInstanceOf[c.universe.FlagSet])
            // tempIdent
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
      val cb = '{ (items: IndexedSeq[Any], ctx: Ctx) =>
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
