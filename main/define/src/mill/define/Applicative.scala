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
  )(caller: Expr[Any], t: Expr[Z[T]]): Expr[M[T]] = {
    import quotes.reflect.*
    impl0(using quotes)(caller.asTerm, t.asTerm)(using
      Type.of[M],
      Type.of[W],
      Type.of[Z],
      Type.of[T],
      Type.of[Ctx]
    )
  }
  def impl0[M[_]: Type, W[_]: Type, Z[_]: Type, T: Type, Ctx: Type](using
      Quotes
  )(caller: quotes.reflect.Tree, t: quotes.reflect.Tree): Expr[M[T]] = {
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

    val defs = extractDefs(t)

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

      val newBody = treeMap.transformTree(t)(Symbol.spliceOwner).asExprOf[Z[T]]
      val exprsList = Expr.ofList(exprs.toList.map(_.asExprOf[W[Any]]))
      (newBody, exprsList)
    }

    /** TraverseCtx is now not a static base type, so we have to resolve it via reflection.
     *  An alternative would be to extract traverseCtx to a generic base trait,
     *  or pass in a function that accepts the traverseCtx arguments.
    */
    def traverseCtxExpr(apps: Expr[Seq[W[Any]]], fn: Expr[(IndexedSeq[Any], Ctx) => Z[T]]): Option[Expr[M[T]]] =
      def defaultError(err: String): Option[Expr[M[T]]] =
        macroError(err, caller.pos)
        None

      def finalResultType(tpe: TypeRepr): TypeRepr = tpe match
        case tpe: LambdaType => finalResultType(tpe.resType)
        case tpe: ByNameType => tpe.underlying
        case _ => tpe

      def lookup(caller0: This, pre: TypeRepr, cls: Symbol): Option[Expr[M[T]]] =
        // traverseCtx[I, R](xs: Seq[Task[I]])(f: (IndexedSeq[I], mill.api.Ctx) => Result[R]): Task[R]
        val bufErrors = List.newBuilder[(String, Position)]
        def bufError(msg: String, pos: Position): Unit = bufErrors += ((msg, pos))
        val candidate = cls.methodMember("traverseCtx").find(m =>
          bufErrors.clear()
          val mTpe = pre.memberType(m)
          m.paramSymss match
            case List(List(i, r), List(xs), List(f)) if i.isType && r.isType && xs.isTerm && f.isTerm =>
              def subParams(tpe: TypeRepr): TypeRepr = {
                // paramSymss types will have Symbol references to type parameters,
                // so we need to substitute them with the actual types
                tpe.substituteTypes(List(i, r), List(TypeRepr.of[Any], TypeRepr.of[T]))
              }
              def applyParams(tpe: TypeRepr): TypeRepr = {
                // mTpe however is computed differently, so doesn't have symbol references, but ParamRefs,
                // so we need a different substitution
                tpe.appliedTo(List(TypeRepr.of[Any], TypeRepr.of[T]))
              }
              def conformsParam[Compare: Type](other: Symbol, sub: Boolean = false): Boolean = {
                val cmp = TypeRepr.of[Compare]
                val otherTpe = {
                  val base = mTpe.memberType(other)
                  if sub then subParams(base) else base
                }
                val res = otherTpe match
                  case TypeBounds(_, hi) => cmp <:< hi
                  case _ => cmp <:< otherTpe
                if !res then
                  val what = s"Parameter ${other.name}: ${otherTpe.show} of ${caller.show}.traverseCtx"
                  bufError(s"$what does not match expected type ${cmp.show}",
                    other.pos.orElse(m.pos).getOrElse(caller0.pos))
                res
              }
              def conformsRes[Compare: Type]: Boolean = {
                val cmp = TypeRepr.of[Compare]
                val resTpe = finalResultType(applyParams(mTpe))
                val res = resTpe <:< cmp
                if !res then
                  val what = s"Result type ${resTpe.show} of ${caller.show}.traverseCtx"
                  bufError(s"$what does not match expected type ${cmp.show}",
                    m.pos.getOrElse(caller0.pos))
                res
              }
              conformsParam[Any](i)
              && conformsParam[T](r)
              && conformsParam[Seq[W[Any]]](xs, sub = true)
              && conformsParam[(IndexedSeq[Any], Ctx) => Z[T]](f, sub = true)
              && conformsRes[M[T]]
            case _ =>
              bufError(s"${caller.show}.traverseCtx method did not have expected parameters", caller.pos)
              false
        )
        candidate.map(m =>
          caller0.select(m) // this.traverseCtx
            .appliedToTypes(List(TypeRepr.of[Any], TypeRepr.of[T])) // ...[Any, T]
            .appliedTo(apps.asTerm) // ...(apps)
            .appliedTo(fn.asTerm) // ...(fn)
            .asExprOf[M[T]]
        ).orElse {
          bufErrors.result().foreach(macroError)
          defaultError(s"Could not find a method `traverseCtx` in prefix ${caller.show}")
        }

      def extractCaller(tree: Tree): Option[This] = tree match
        case t: This => Some(t)
        case Inlined(_, _, t) => extractCaller(t)
        case Block(_, expr) => extractCaller(expr)
        case _ => None

      extractCaller(caller) match
        case Some(t: This) => t.tpe match
          case pre @ ThisType(ref) => ref.classSymbol match
            case Some(cls) => lookup(t, pre, cls)
            case None => defaultError("Could not resolve class of `this`")
          case _ => defaultError("Unexpected type of `this`, not a `ThisType`")
        case _ => defaultError(s"Expected a `this` reference, found ${caller.show}")
    end traverseCtxExpr

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

    val calledTraverse = traverseCtxExpr(exprsList, callback)

    if hasErrors || calledTraverse.isEmpty then
      '{ throw new RuntimeException("stub implementation - macro expansion had errors") }
    else
      // TODO: make caller Expr[C] (C is generic param), and look up the method with quotes.reflect, checking types,
      // then it can be generic.
      // or, probably pass the traverseCtx method call as a Expr[<function>]
      //${ callerExpr }.traverseCtx[Any, T]($exprsList.asInstanceOf[Seq[mill.define.Task[Any]]])($callback.asInstanceOf[(IndexedSeq[Any], mill.api.Ctx) => mill.api.Result[T]]).asInstanceOf[M[T]] }
      calledTraverse.get
  }

}
