package mill.moduledefs

import scala.collection.mutable
import scala.reflect.macros.blackbox.Context


trait Cacher{
  private[this] lazy val cacherLazyMap = mutable.Map.empty[sourcecode.Enclosing, Any]

  protected[this] def cachedTarget[T](t: => T)
                                     (implicit c: sourcecode.Enclosing): T = synchronized{
    cacherLazyMap.getOrElseUpdate(c, t).asInstanceOf[T]
  }
}

object Cacher{
  def impl0[T: c.WeakTypeTag](c: Context)
                                   (t: c.Expr[T]): c.Expr[T] = {
    c.Expr[T](wrapCached[T](c)(t.tree))
  }
  def wrapCached[R: c.WeakTypeTag](c: Context)(t: c.Tree) = {

    import c.universe._
    val owner = c.internal.enclosingOwner
    val ownerIsCacherClass =
      owner.owner.isClass &&
        owner.owner.asClass.baseClasses.exists(_.fullName == "mill.moduledefs.Cacher")

    if (ownerIsCacherClass && owner.isMethod) q"this.cachedTarget[${weakTypeTag[R]}]($t)"
    else c.abort(
      c.enclosingPosition,
      "T{} members must be defs defined in a Cacher class/trait/object body"
    )
  }
}