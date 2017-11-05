package forge.define

import forge.util.Args

import scala.annotation.compileTimeOnly
import scala.collection.mutable
import scala.reflect.macros.blackbox.Context

object Applicative {
  trait Applyable[T]{
    @compileTimeOnly("Target#apply() can only be used with a T{...} block")
    def apply(): T = ???
  }
  trait Applyer[T[_]]{
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
  trait Cacher[C]{
    private[this] val cacherLazyMap = mutable.Map.empty[sourcecode.Enclosing, C]
    protected[this] def cachedTarget[T <: C](t: => T)
                                       (implicit c: sourcecode.Enclosing): T = synchronized{
      cacherLazyMap.getOrElseUpdate(c, t).asInstanceOf[T]
    }
  }

  def impl0[M[_], T: c.WeakTypeTag](c: Context)(t: c.Expr[M[T]]): c.Expr[M[T]] = {
    wrapCached(c)(t.tree)
  }
  def impl[M[_], T: c.WeakTypeTag](c: Context)(t: c.Expr[T])(implicit tt: c.WeakTypeTag[M[_]]): c.Expr[M[T]] = {
    import c.universe._
    def rec(t: Tree): Iterator[c.Tree] = Iterator(t) ++ t.children.flatMap(rec(_))

    val bound = collection.mutable.Buffer.empty[(c.Tree, Symbol)]
    val targetApplySym = typeOf[Applyable[_]].member(TermName("apply"))

    // Derived from @olafurpg's
    // https://gist.github.com/olafurpg/596d62f87bf3360a29488b725fbc7608
    val (startPos, endPos) = rec(t.tree)
      .map(t => (t.pos.start, t.pos.end))
      .reduce[(Int, Int)]{ case ((s1, e1), (s2, e2)) => (math.min(s1, s2), math.max(e1, e2))}

    val macroSource = t.tree.pos.source
    val transformed = c.internal.typingTransform(t.tree) {
      case (t @ q"$fun.apply()", api) if t.symbol == targetApplySym =>

        val used = rec(t)
        val banned = used.filter(x =>
          x.symbol.pos.source == macroSource &&
            x.symbol.pos.start >= startPos &&
            x.symbol.pos.end <= endPos
        )
        if (banned.hasNext){
          val banned0 = banned.next()
          c.abort(
            banned0.pos,
            "Target#apply() call cannot use `" + banned0.symbol + "` defined within the T{...} block"
          )
        }
        val tempName = c.freshName(TermName("tmp"))
        val tempSym = c.internal.newTermSymbol(api.currentOwner, tempName)
        c.internal.setInfo(tempSym, t.tpe)
        val tempIdent = Ident(tempSym)
        c.internal.setType(tempIdent, t.tpe)
        bound.append((fun, tempSym))
        tempIdent
      case (t, api) => api.default(t)
    }

    val (exprs, symbols) = bound.unzip

    val bindings = symbols.map(c.internal.valDef(_))

    wrapCached(c)(q"${c.prefix}.zipMap(..$exprs){ (..$bindings) => $transformed }")
  }
  def wrapCached[M[_], T](c: Context)(t: c.Tree) = {
    import c.universe._
    val owner = c.internal.enclosingOwner
    val ownerIsCacherClass =
      owner.owner.isClass &&
        owner.owner.asClass.baseClasses.exists(_.fullName == "forge.define.Applicative.Cacher")

    if (ownerIsCacherClass && !owner.isMethod){
      c.abort(
        c.enclosingPosition,
        "T{} members defined in a Cacher class/trait/object body must be defs"
      )
    }else{
      val embedded =
        if (!ownerIsCacherClass) t
        else q"this.cachedTarget($t)"

      c.Expr[M[T]](embedded)
    }
  }
}
