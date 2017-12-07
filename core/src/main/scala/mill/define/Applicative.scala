package mill.define


import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.reflect.macros.blackbox.Context

/**
  * A generic Applicative-functor macro: translates calls to
  *
  * Applier.apply{ ... applyable1.apply() ... applyable2.apply() ... }
  *
  * into
  *
  * Applier.zipMap(applyable1, applyable2){ (a1, a2, ctx) => ... a1 ... a2 ... }
  */
object Applicative {
  trait Applyable[+T]{
    @compileTimeOnly("Target#apply() can only be used with a T{...} block")
    def apply(): T = ???
  }
  class ImplicitStub extends StaticAnnotation
  type Id[+T] = T

  trait Applyer[W[_], T[_], Z[_], Ctx]{
    def ctx()(implicit c: Ctx) = c
    def underlying[A](v: W[A]): T[_]

    def mapCtx[A, B](a: T[A])(f: (A, Ctx) => Z[B]): T[B]
    def zipMap[R]()(cb: Ctx => Z[R]) = mapCtx(zip()){ (_, ctx) => cb(ctx)}
    def zipMap[A, R](a: T[A])(f: (A, Ctx) => Z[R]) = mapCtx(a)(f)
    def zipMap[A, B, R](a: T[A], b: T[B])(cb: (A, B, Ctx) => Z[R]) = mapCtx(zip(a, b)){case ((a, b), x) => cb(a, b, x)}
    def zipMap[A, B, C, R](a: T[A], b: T[B], c: T[C])
                          (cb: (A, B, C, Ctx) => Z[R]) = mapCtx(zip(a, b, c)){case ((a, b, c), x) => cb(a, b, c, x)}
    def zipMap[A, B, C, D, R](a: T[A], b: T[B], c: T[C], d: T[D])
                             (cb: (A, B, C, D, Ctx) => Z[R]) = mapCtx(zip(a, b, c, d)){case ((a, b, c, d), x) => cb(a, b, c, d, x)}
    def zipMap[A, B, C, D, E, R](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E])
                                (cb: (A, B, C, D, E, Ctx) => Z[R]) = mapCtx(zip(a, b, c, d, e)){case ((a, b, c, d, e), x) => cb(a, b, c, d, e, x)}
    def zipMap[A, B, C, D, E, F, R](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F])
                                   (cb: (A, B, C, D, E, F, Ctx) => Z[R]) = mapCtx(zip(a, b, c, d, e, f)){case ((a, b, c, d, e, f), x) => cb(a, b, c, d, e, f, x)}
    def zipMap[A, B, C, D, E, F, G, H, R](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F], g: T[G], h: T[H])
                                      (cb: (A, B, C, D, E, F, G, H, Ctx) => Z[R]) = mapCtx(zip(a, b, c, d, e, f, g, h)){case ((a, b, c, d, e, f, g, h), x) => cb(a, b, c, d, e, f, g, h, x)}
    def zip(): T[Unit]
    def zip[A](a: T[A]): T[Tuple1[A]]
    def zip[A, B](a: T[A], b: T[B]): T[(A, B)]
    def zip[A, B, C](a: T[A], b: T[B], c: T[C]): T[(A, B, C)]
    def zip[A, B, C, D](a: T[A], b: T[B], c: T[C], d: T[D]): T[(A, B, C, D)]
    def zip[A, B, C, D, E](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E]): T[(A, B, C, D, E)]
    def zip[A, B, C, D, E, F](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F]): T[(A, B, C, D, E, F)]
    def zip[A, B, C, D, E, F, G, H](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F], g: T[G], h: T[H]): T[(A, B, C, D, E, F, G, H)]
  }

  def impl[M[_], T: c.WeakTypeTag, Ctx: c.WeakTypeTag](c: Context)
                                                      (t: c.Expr[T]): c.Expr[M[T]] = {
    impl0(c)(t.tree)(implicitly[c.WeakTypeTag[T]], implicitly[c.WeakTypeTag[Ctx]])
  }
  def impl0[M[_], T: c.WeakTypeTag, Ctx: c.WeakTypeTag](c: Context)
                                                       (t: c.Tree): c.Expr[M[T]] = {
    import c.universe._
    def rec(t: Tree): Iterator[c.Tree] = Iterator(t) ++ t.children.flatMap(rec(_))

    val bound = collection.mutable.Buffer.empty[(c.Tree, ValDef)]
    val targetApplySym = typeOf[Applyable[_]].member(TermName("apply"))

    // Derived from @olafurpg's
    // https://gist.github.com/olafurpg/596d62f87bf3360a29488b725fbc7608
    val defs = rec(t).filter(_.isDef).map(_.symbol).toSet

    val ctxName = TermName(c.freshName("ctx"))
    val ctxSym = c.internal.newTermSymbol(c.internal.enclosingOwner, ctxName)
    c.internal.setInfo(ctxSym, weakTypeOf[Ctx])

    val transformed = c.internal.typingTransform(t) {
      case (t @ q"$fun.apply()", api) if t.symbol == targetApplySym =>

        val localDefs = rec(fun).filter(_.isDef).map(_.symbol).toSet
        val banned = rec(t).filter(x => defs(x.symbol) && !localDefs(x.symbol))

        if (banned.hasNext){
          val banned0 = banned.next()
          c.abort(
            banned0.pos,
            "Target#apply() call cannot use `" + banned0.symbol + "` defined within the T{...} block"
          )
        }
        val tempName = c.freshName(TermName("tmp"))
        val tempSym = c.internal.newTermSymbol(c.internal.enclosingOwner, tempName)
        c.internal.setInfo(tempSym, t.tpe)
        val tempIdent = Ident(tempSym)
        c.internal.setType(tempIdent, t.tpe)
        c.internal.setFlag(tempSym, (1L << 44).asInstanceOf[c.universe.FlagSet])
        bound.append((q"${c.prefix}.underlying($fun)", c.internal.valDef(tempSym)))
        tempIdent
      case (t, api)
        if t.symbol != null
        && t.symbol.annotations.exists(_.tree.tpe =:= typeOf[ImplicitStub]) =>

        val tempIdent = Ident(ctxSym)
        c.internal.setType(tempIdent, t.tpe)
        c.internal.setFlag(ctxSym, (1L << 44).asInstanceOf[c.universe.FlagSet])
        tempIdent

      case (t, api) => api.default(t)
    }

    val (exprs, bindings) = bound.unzip


    val ctxBinding = c.internal.valDef(ctxSym)

    val callback = c.typecheck(q"(..$bindings, $ctxBinding) => $transformed ")

    val res = q"${c.prefix}.zipMap(..$exprs){ $callback }"

    c.internal.changeOwner(transformed, c.internal.enclosingOwner, callback.symbol)

    c.Expr[M[T]](res)
  }

}
