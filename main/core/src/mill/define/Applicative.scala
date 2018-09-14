package mill.define

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.language.higherKinds
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
  trait ApplyHandler[M[+_]]{
    def apply[T](t: M[T]): T
  }
  object ApplyHandler{
    @compileTimeOnly("Target#apply() can only be used with a T{...} block")
    implicit def defaultApplyHandler[M[+_]]: ApplyHandler[M] = ???
  }
  trait Applyable[M[+_], +T]{
    def self: M[T]
    def apply()(implicit handler: ApplyHandler[M]): T = handler(self)
  }
  class ImplicitStub extends StaticAnnotation
  type Id[+T] = T

  trait Applyer[W[_], T[_], Z[_], Ctx] extends ApplyerGenerated[T, Z, Ctx] {
    def ctx()(implicit c: Ctx) = c
    def underlying[A](v: W[A]): T[_]

    def zipMap[R]()(cb: Ctx => Z[R]) = mapCtx(zip()){ (_, ctx) => cb(ctx)}
    def zipMap[A, R](a: T[A])(f: (A, Ctx) => Z[R]) = mapCtx(a)(f)
    def zip(): T[Unit]
    def zip[A](a: T[A]): T[Tuple1[A]]
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
    val targetApplySym = typeOf[Applyable[Nothing, _]].member(TermName("apply"))

    // Derived from @olafurpg's
    // https://gist.github.com/olafurpg/596d62f87bf3360a29488b725fbc7608
    val defs = rec(t).filter(_.isDef).map(_.symbol).toSet

    val ctxName = TermName(c.freshName("ctx"))
    val ctxSym = c.internal.newTermSymbol(c.internal.enclosingOwner, ctxName)
    c.internal.setInfo(ctxSym, weakTypeOf[Ctx])

    val transformed = c.internal.typingTransform(t) {
      case (t @ q"$fun.apply()($handler)", api) if t.symbol == targetApplySym =>

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
