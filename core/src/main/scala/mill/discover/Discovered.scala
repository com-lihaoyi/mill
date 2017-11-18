package mill.discover

import mill.define.Task.Module
import mill.define.{Target, Task}
import mill.discover.Mirror.LabelledTarget
import mill.discover.Router.{EntryPoint, Result}

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context


class Discovered[T](val mirror: Mirror[T, T]){

  def targets(obj: T) = Mirror.traverse(mirror) { (h, p) =>
    h.labelled(obj, p)
  }

  def mains = Mirror.traverse(mirror) { (h, p) =>
    h.commands.map(x => (p, x: EntryPoint[_]))
  }

}

object Discovered {
  def consistencyCheck[T](base: T, d: Discovered[T]) = {
    val inconsistent = for{
      (t1, t2) <- d.targets(base).zip(d.targets(base))
      if t1.target ne t2.target
    } yield t1.segments
    inconsistent
  }



  def mapping[T: Discovered](t: T): Map[Task[_], LabelledTarget[_]] = {
    implicitly[Discovered[T]].targets(t).map(x => x.target -> x).toMap
  }

  implicit def apply[T]: Discovered[T] = macro applyImpl[T]

  def applyImpl[T: c.WeakTypeTag](c: Context): c.Expr[Discovered[T]] = {
    import c.universe._
    val tpe = c.weakTypeTag[T].tpe
    def rec(segments: List[String],
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
        q"""mill.discover.Mirror.makeTargetPoint(
          ${m.name.toString},
          ($x: ${m.typeSignature.resultType}) => $x.${m.name.toTermName}
        )"""
      }



      val childHierarchies = for{
        m <- t.members.toList
        if m.typeSignature <:< c.weakTypeOf[Module]
      } yield {
        val name = m.name.toString.trim
        q"($name, ${rec(name :: segments, m.typeSignature)})"
      }

      val hierarchySelector = {
        val base = q"${TermName(c.freshName())}"
        val ident = segments.reverse.foldLeft[Tree](base) { (prefix, name) =>
          q"$prefix.${TermName(name)}"
        }
        q"($base: $tpe) => $ident"
      }

      val commands =
        r.getAllRoutesForClass(t.asInstanceOf[r.c.Type])
          .asInstanceOf[Seq[c.Tree]]
          .toList

      q"""mill.discover.Mirror[$tpe, $t](
        $hierarchySelector,
        $commands,
        $targets,
        $childHierarchies
      )"""
    }



    c.Expr[Discovered[T]](q"new _root_.mill.discover.Discovered(${rec(Nil, tpe)})")
  }
}
