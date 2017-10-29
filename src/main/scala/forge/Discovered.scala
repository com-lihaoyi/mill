package forge

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

class Discovered[T](val value: Seq[(Seq[String], T => Target[_])]){
  def apply(t: T) = value.map{case (a, b) => (a, b(t)) }

}
object Discovered {
  def mapping[T: Discovered](t: T): Map[Target[_], Seq[String]] = {
    implicitly[Discovered[T]].apply(t).map(_.swap).toMap
  }

  implicit def apply[T]: Discovered[T] = macro applyImpl[T]

  def applyImpl[T: c.WeakTypeTag](c: Context): c.Expr[Discovered[T]] = {
    import c.universe._
    val tpe = c.weakTypeTag[T].tpe
    def rec(segments: List[String], t: c.Type): Seq[Seq[String]] = for {
      m <- t.members.toSeq
      if m.isTerm && (m.asTerm.isGetter || m.asTerm.isLazy) || m.isModule
      res <- {
        val extendedSegments = m.name.toString :: segments
        val self =
          if (m.typeSignature.resultType <:< c.weakTypeOf[Target[_]]) Seq(extendedSegments)
          else Nil
        val children = rec(extendedSegments, m.typeSignature)
        self ++ children
      }
    } yield res

    val reversedPaths = rec(Nil, tpe)

    val result = for(reversedPath <- reversedPaths.toList) yield {
      val base = q"${TermName(c.freshName())}"
      val segments = reversedPath.reverse.toList
      val ident = segments.foldLeft[Tree](base)((prefix, name) =>
        q"$prefix.${TermName(name)}"
      )
      q"($segments, ($base: $tpe) => $ident)"
    }

    c.Expr[Discovered[T]](q"new _root_.forge.Discovered($result)")
  }
}
