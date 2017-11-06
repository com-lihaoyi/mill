package forge.define

import scala.collection.mutable
import scala.reflect.macros.blackbox.Context


trait Cacher[C]{
  private[this] val cacherLazyMap = mutable.Map.empty[sourcecode.Enclosing, C]
  protected[this] def cachedTarget[T <: C](t: => T)
                                          (implicit c: sourcecode.Enclosing): T = synchronized{
    cacherLazyMap.getOrElseUpdate(c, t).asInstanceOf[T]
  }
}
object Cacher{
  def impl0[M[_], T: c.WeakTypeTag](c: Context)(t: c.Expr[M[T]]): c.Expr[M[T]] = {
    wrapCached(c)(t)
  }
  def wrapCached[M[_], T](c: Context)(t: c.Expr[M[T]]) = {
    import c.universe._
    val owner = c.internal.enclosingOwner
    val ownerIsCacherClass =
      owner.owner.isClass &&
      owner.owner.asClass.baseClasses.exists(_.fullName == "forge.define.Cacher")

    if (ownerIsCacherClass && !owner.isMethod){
      c.abort(
        c.enclosingPosition,
        "T{} members defined in a Cacher class/trait/object body must be defs"
      )
    }else{
      if (!ownerIsCacherClass) t
      else c.Expr[M[T]](q"this.cachedTarget($t)")
    }
  }
}