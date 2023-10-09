package mill.define

import mill.api.internal

import scala.annotation.compileTimeOnly
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
@internal
object Applicative {
  trait ApplyHandler[M[+_]] {

    /**
     * Extracts the current value [[T]] out of the wrapping [[M[T]]
     */
    def apply[T](t: M[T]): T
  }
  object ApplyHandler {
    @compileTimeOnly("Target#apply() can only be used with a T{...} block")
    implicit def defaultApplyHandler[M[+_]]: ApplyHandler[M] = ???
  }
  trait Applyable[M[+_], +T] {
    def self: M[T]
    def apply()(implicit handler: ApplyHandler[M]): T = handler(self)
  }

  type Id[+T] = T

  trait Applyer[W[_], T[_], Z[_], Ctx] {
    def ctx()(implicit c: Ctx) = c
    def traverseCtx[I, R](xs: Seq[W[I]])(f: (IndexedSeq[I], Ctx) => Z[R]): T[R]
  }

  def impl[M[_], T: c.WeakTypeTag, Ctx: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[M[T]] = {
    impl0(c)(t.tree)(implicitly[c.WeakTypeTag[T]], implicitly[c.WeakTypeTag[Ctx]])
  }
  def impl0[M[_], T: c.WeakTypeTag, Ctx: c.WeakTypeTag](c: Context)(t: c.Tree): c.Expr[M[T]] = {
    import c.universe._
    def rec(t: Tree): Iterator[c.Tree] = Iterator(t) ++ t.children.flatMap(rec(_))

    val exprs = collection.mutable.Buffer.empty[c.Tree]
    val targetApplySym = typeOf[Applyable[Nothing, _]].member(TermName("apply"))

    val itemsName = c.freshName(TermName("items"))
    val itemsSym = c.internal.newTermSymbol(c.internal.enclosingOwner, itemsName)
    c.internal.setFlag(itemsSym, (1L << 44).asInstanceOf[c.universe.FlagSet])
    c.internal.setInfo(itemsSym, typeOf[Seq[Any]])
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

        if (banned.hasNext) {
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
        val itemsIdent = Ident(itemsSym)
        exprs.append(q"$fun")
        c.typecheck(q"$itemsIdent(${exprs.size - 1}).asInstanceOf[${t.tpe}]")
      case (t, api)
          if t.symbol != null
            && t.symbol.annotations.exists(_.tree.tpe =:= typeOf[mill.api.Ctx.ImplicitStub]) =>
        val tempIdent = Ident(ctxSym)
        c.internal.setType(tempIdent, t.tpe)
        c.internal.setFlag(ctxSym, (1L << 44).asInstanceOf[c.universe.FlagSet])
        tempIdent

      case (t, api) => api.default(t)
    }

    val ctxBinding = c.internal.valDef(ctxSym)

    val itemsBinding = c.internal.valDef(itemsSym)
    val callback = c.typecheck(q"{(${itemsBinding}, ${ctxBinding}) => $transformed}")

    val res =
      q"${c.prefix}.traverseCtx[_root_.scala.Any, ${weakTypeOf[T]}](${exprs.toList}){ $callback }"

    c.internal.changeOwner(transformed, c.internal.enclosingOwner, callback.symbol)

    c.Expr[M[T]](res)
  }

}
