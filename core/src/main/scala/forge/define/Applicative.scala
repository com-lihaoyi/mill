package forge.define

import forge.util.Args

import scala.annotation.compileTimeOnly
import scala.collection.mutable
import scala.reflect.macros.blackbox.Context

object Applicative {
  trait Applyable[+T]{
    @compileTimeOnly("Target#apply() can only be used with a T{...} block")
    def apply(): T = ???
  }
  trait Applyer[W[_], T[_]]{
    def underlying[A](v: W[A]): T[_]

    def map[A, B](a: T[A], f: A => B): T[B]
    def zipMap[R]()(f: () => R) = map(zip(), (_: Unit) => f())
    def zipMap[A, R](a: T[A])(f: A => R) = map(a, f)
    def zipMap[A, B, R](a: T[A], b: T[B])(f: (A, B) => R) = map(zip(a, b), f.tupled)
    def zipMap[A, B, C, R](a: T[A], b: T[B], c: T[C])
                          (f: (A, B, C) => R) = map(zip(a, b, c), f.tupled)
    def zipMap[A, B, C, D, R](a: T[A], b: T[B], c: T[C], d: T[D])
                             (f: (A, B, C, D) => R) = map(zip(a, b, c, d), f.tupled)
    def zipMap[A, B, C, D, E, R](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E])
                                (f: (A, B, C, D, E) => R) = map(zip(a, b, c, d, e), f.tupled)
    def zipMap[A, B, C, D, E, F, R](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F])
                                   (cb: (A, B, C, D, E, F) => R) = map(zip(a, b, c, d, e, f), cb.tupled)
    def zipMap[A, B, C, D, E, F, G, R](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F], g: T[G])
                                      (cb: (A, B, C, D, E, F, G) => R) = map(zip(a, b, c, d, e, f, g), cb.tupled)
    def zip(): T[Unit]
    def zip[A](a: T[A]): T[Tuple1[A]]
    def zip[A, B](a: T[A], b: T[B]): T[(A, B)]
    def zip[A, B, C](a: T[A], b: T[B], c: T[C]): T[(A, B, C)]
    def zip[A, B, C, D](a: T[A], b: T[B], c: T[C], d: T[D]): T[(A, B, C, D)]
    def zip[A, B, C, D, E](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E]): T[(A, B, C, D, E)]
    def zip[A, B, C, D, E, F](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F]): T[(A, B, C, D, E, F)]
    def zip[A, B, C, D, E, F, G](a: T[A], b: T[B], c: T[C], d: T[D], e: T[E], f: T[F], g: T[G]): T[(A, B, C, D, E, F, G)]
  }

  def impl[M[_], T: c.WeakTypeTag](c: Context)
                                  (t: c.Expr[T]): c.Expr[M[T]] = {
    import c.universe._
    def rec(t: Tree): Iterator[c.Tree] = Iterator(t) ++ t.children.flatMap(rec(_))

    val bound = collection.mutable.Buffer.empty[(c.Tree, ValDef)]
    val targetApplySym = typeOf[Applyable[_]].member(TermName("apply"))

    // Derived from @olafurpg's
    // https://gist.github.com/olafurpg/596d62f87bf3360a29488b725fbc7608
    val defs = rec(t.tree).filter(_.isDef).map(_.symbol).toSet

    val transformed = c.internal.typingTransform(t.tree) {
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
      case (t, api) => api.default(t)
    }

    val (exprs, bindings) = bound.unzip


    val callback = c.typecheck(q"(..$bindings) => $transformed ")

    val res = q"${c.prefix}.zipMap(..$exprs){ $callback }"

    c.internal.changeOwner(transformed, c.internal.enclosingOwner, callback.symbol)

    c.Expr[M[T]](res)
  }

}
