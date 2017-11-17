package mill.discover

import mill.define.Task.Module
import mill.define.{Target, Task}
import mill.discover.Router.{EntryPoint, Result}

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

class Discovered[T](val hierarchy: Hierarchy[T, _]){
  def targets(obj: T) = {
    def rec[V](segmentsRev: List[String], h: Hierarchy[T, V]): Seq[Labelled[_]]= {
      val self = h.targets.map(t =>
        t.labelled(h.node(obj), (t.label :: segmentsRev).reverse)
      )
      self ++ h.children.flatMap{case (label, c) => rec(label :: segmentsRev, c)}
    }
    rec(Nil, hierarchy)
  }
  def mains = {
    def rec[V](segmentsRev: List[String], h: Hierarchy[T, V]): Seq[(Seq[String], EntryPoint[_])]= {
      h.commands.map((segmentsRev.reverse, _)) ++
      h.children.flatMap{case (label, c) => rec(label :: segmentsRev, c)}
    }
    rec(Nil, hierarchy)
  }
}

case class Hierarchy[T, V](node: T => V,
                           commands: Seq[EntryPoint[V]],
                           targets: Seq[TargetInfo[V, _]],
                           children: List[(String, Hierarchy[T, _])])

case class Labelled[T](target: Task[T],
                       format: upickle.default.ReadWriter[T],
                       segments: Seq[String])

case class TargetInfo[T, V](label: String,
                            format: upickle.default.ReadWriter[V],
                            run: T => Task[V]) {
  def labelled(t: T, segments: Seq[String]) = Labelled(run(t), format, segments)
}
object TargetInfo{
  def make[T, V](label: String, func: T => Task[V])
                (implicit f: upickle.default.ReadWriter[V]) = {
    TargetInfo(label, f, func)
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



  def mapping[T: Discovered](t: T): Map[Task[_], Labelled[_]] = {
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
        q"mill.discover.TargetInfo.make(${m.name.toString}, ($x: ${m.typeSignature.resultType}) => $x.${m.name.toTermName})"
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

      q"""mill.discover.Hierarchy[$tpe, $t](
        $hierarchySelector,
        $commands,
        $targets,
        $childHierarchies
      )"""
    }



    c.Expr[Discovered[T]](q"new _root_.mill.discover.Discovered(${rec(Nil, tpe)})")
  }
}
