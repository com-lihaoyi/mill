package mill.define

import scala.collection.mutable
import scala.reflect.macros.blackbox.Context


trait Cacher[C[_], V[_]]{
  private[this] val cacherLazyMap = mutable.Map.empty[sourcecode.Enclosing, V[_]]
  def wrapCached[T](in: C[T]): V[T]
  protected[this] def cachedTarget[T](t: => C[T])
                                     (implicit c: sourcecode.Enclosing): V[T] = synchronized{
    cacherLazyMap.getOrElseUpdate(c, wrapCached(t)).asInstanceOf[V[T]]
  }
}
object Cacher{
  def impl0[M[_], T: c.WeakTypeTag](c: Context)(t: c.Expr[M[T]]): c.Expr[M[T]] = {
    c.Expr[M[T]](wrapCached(c)(t.tree))
  }
  def wrapCached(c: Context)(t: c.Tree) = {
    import c.universe._
    val owner = c.internal.enclosingOwner
    val ownerIsCacherClass =
      owner.owner.isClass &&
      owner.owner.asClass.baseClasses.exists(_.fullName == "mill.define.Cacher")

    if (ownerIsCacherClass && owner.isMethod) q"this.cachedTarget($t)"
    else c.abort(
      c.enclosingPosition,
      "T{} members must be defs defined in a Cacher class/trait/object body"
    )
  }
}