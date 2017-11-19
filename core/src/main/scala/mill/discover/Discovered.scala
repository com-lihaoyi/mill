package mill.discover

import mill.define.Task.Module
import mill.define.{Cross, Target, Task}
import mill.discover.Mirror.LabelledTarget
import mill.discover.Router.{EntryPoint, Result}

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

/**
  * Allows you to implicitly summon up a build [[Mirror]] for arbitrary types
  */
class Discovered[T](val mirror: Mirror[T, T]){

  def targets(obj: T, crosses: List[List[Any]]) = Mirror.traverse(mirror) { (h, p) =>
    h.labelled(obj, p, crosses)
  }

}

object Discovered {
  def consistencyCheck[T](base: T, d: Discovered[T]) = {
    val inconsistent = for{
      (t1, t2) <- d.targets(base, Nil).zip(d.targets(base, Nil))
      if t1.target ne t2.target
    } yield t1.segments
    inconsistent
  }


  def mapping[T: Discovered](t: T): Map[Target[_], LabelledTarget[_]] = {
    implicitly[Discovered[T]].targets(t, Nil).map(x => x.target -> x).toMap
  }

  implicit def apply[T]: Discovered[T] = macro applyImpl[T]
  def tupleLeft[T, V](items: List[(T, V)]) = items.map(_._1)
  def applyImpl[T: c.WeakTypeTag](c: Context): c.Expr[Discovered[T]] = {
    import c.universe._
    val tpe = c.weakTypeTag[T].tpe
    def rec(segments: List[Option[String]],
            t: c.Type): Tree = {

      val r = new Router(c)


      val targets = for {
        m <- t.members.toList
        if m.isMethod &&
           m.typeSignature.paramLists.isEmpty &&
           m.typeSignature.resultType <:< c.weakTypeOf[Target[_]] &&
           !m.name.toString.contains('$') &&
           !m.name.toString.contains(' ')
      } yield {
        val x = Ident(TermName(c.freshName()))
        val t = q"""mill.discover.Mirror.makeTargetPoint(
          ${m.name.toString},
          ($x: ${m.typeSignature.resultType}) => $x.${m.name.toTermName}
        )"""

        c.internal.setPos(t, m.pos)
        t
      }

      val crossChildren =
        if (!(t <:< c.weakTypeOf[Cross[Module]])) q"None"
        else {

          val TypeRef(_, _, Seq(arg)) = t
          val innerMirror = rec(None :: segments, arg)
          q"Some(((c: mill.define.Cross[_]) => mill.discover.Discovered.tupleLeft(c.items), $innerMirror))"
        }
      val childHierarchies = for{
        m <- t.members.toList
        if (m.typeSignature <:< c.weakTypeOf[Module]) ||
           (m.typeSignature <:< c.weakTypeOf[Cross[Module]])
      } yield {
        val name = m.name.toString.trim
        q"($name, ${rec(Some(name) :: segments, m.typeSignature)})"
      }

      val hierarchySelector = {
        val base = q"${TermName(c.freshName())}"
        val ident = segments.reverse.foldLeft[Tree](base) {
          case (prefix, Some(name)) => q"$prefix.${TermName(name)}"
          case (prefix, None) => q"$prefix.apply(crosses.head)"
        }
        q"($base: $tpe, crosses: List[List[Any]]) => $ident"
      }

      val commands =
        r.getAllRoutesForClass(t.asInstanceOf[r.c.Type])
          .asInstanceOf[Seq[c.Tree]]
          .toList

      q"""mill.discover.Mirror[$tpe, $t](
        $hierarchySelector,
        $commands,
        $targets,
        $childHierarchies,
        $crossChildren
      )"""
    }

    val res = q"new _root_.mill.discover.Discovered(${rec(Nil, tpe)})"
//    println(res)
    c.Expr[Discovered[T]](res)
  }
}
