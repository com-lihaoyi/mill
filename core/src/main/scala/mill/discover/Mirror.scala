package mill.discover

import mill.define.{Segment, Segments, Target, Task}
import ammonite.main.Router.EntryPoint

import scala.language.experimental.macros

/**
  * Metadata about a build that is extracted & materialized at compile-time,
  * letting us use it at run-time without needing to use the heavy weight
  * scala-reflect library.
  *
  * Note that [[Mirror]] allows you to store and inspect metadata of a type
  * [[T]] even without a concrete instance of [[T]] itself.
  */
case class Mirror[-T, V](node: (T, List[List[Any]]) => V,
                         commands: Seq[EntryPoint[V]],
                         children: List[(String, Mirror[T, _])],
                         crossChildren: Option[(V => List[List[Any]], Mirror[T, _])])

object Mirror{

  def traverseNode[T, V, R](t: T, hierarchy: Mirror[T, V])
                           (f: (Mirror[T, _], => Segments, => Any) => Seq[R]): Seq[R] = {
    traverse(t, hierarchy){ (mirror, segmentsRev) =>
      lazy val resolvedNode = mirror.node(
        t,
        segmentsRev.value.map{case Segment.Cross(vs) => vs.toList case _ => Nil}.toList
      )
      f(mirror, segmentsRev, resolvedNode)
    }
  }
  def traverse[T, V, R](t: T, hierarchy: Mirror[T, V])
                       (f: (Mirror[T, _], => Segments) => Seq[R]): Seq[R] = {
    def rec[C](segmentsRev: List[Segment], h: Mirror[T, C]): Seq[R]= {
      val crossValues = segmentsRev.map{case Segment.Cross(vs) => vs case _ => Nil}
      val self = f(h, Segments(segmentsRev.reverse:_*))
      self ++
      h.children.flatMap{case (label, c) => rec(Segment.Label(label) :: segmentsRev, c)} ++
      h.crossChildren.toSeq.flatMap{
        case (crossGen, c) =>
          crossGen(h.node(t, crossValues.reverse.map(_.toList))).flatMap(cross =>
            rec(Segment.Cross(cross) :: segmentsRev, c)
          )
      }
    }
    rec(Nil, hierarchy)
  }
}
