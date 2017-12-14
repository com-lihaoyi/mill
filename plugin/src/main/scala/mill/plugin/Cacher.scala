package mill.plugin

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.language.higherKinds
import scala.reflect.macros.blackbox


trait Cacher[C[_]]{
  private[this] val cacherLazyMapRef = new AtomicReference[Map[sourcecode.Enclosing, C[_]]](Map.empty[sourcecode.Enclosing, C[_]])

  def wrapCached[T](in: C[T], enclosing: String): C[T]
  protected[this] def cachedTarget[T](t: => C[T])
                                     (implicit c: sourcecode.Enclosing): C[T] = {
    @tailrec
    def getOrCacheTarget(ct: C[T] = null.asInstanceOf[C[T]]):C[T] = {
      val cacherLazyMap = cacherLazyMapRef.get()
      cacherLazyMap.get(c) match {
        case Some(cached) => cached.asInstanceOf[C[T]]
        case None =>
          val unCached = if (ct == null) wrapCached(t, c.value) else ct
          if (cacherLazyMapRef.compareAndSet(cacherLazyMap,cacherLazyMap.updated(c,unCached))){
            unCached
          }else{
            getOrCacheTarget(unCached)
          }
      }
    }
    getOrCacheTarget()
  }
}
object Cacher{
  def impl0[M[_], T: c.WeakTypeTag](c: blackbox.Context)(t: c.Expr[M[T]]): c.Expr[M[T]] = {
    c.Expr[M[T]](wrapCached(c)(t.tree))
  }
  def wrapCached(c: blackbox.Context)(t: c.Tree): c.universe.Tree = {

    import c.universe._
    val owner = c.internal.enclosingOwner
    val ownerIsCacherClass =
      owner.owner.isClass &&
        owner.owner.asClass.baseClasses.exists(_.fullName == "mill.plugin.Cacher")

    if (ownerIsCacherClass && owner.isMethod) q"this.cachedTarget($t)"
    else c.abort(
      c.enclosingPosition,
      "T{} members must be defs defined in a Cacher class/trait/object body"
    )
  }
}