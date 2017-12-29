package mill.discover

import mill.define.{Target, Task}
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
                         targets: Seq[Mirror.TargetPoint[V, _]],
                         children: List[(String, Mirror[T, _])],
                         crossChildren: Option[(V => List[List[Any]], Mirror[T, _])]){
  def labelled(obj: T, p: Seq[Mirror.Segment]) = {
    val crossValues = p.map{case Mirror.Segment.Cross(vs) => vs case _ => Nil}.toList
    targets.map(t => t.labelled(node(obj, crossValues.reverse.map(_.toList)), p.reverse))
  }
}

object Mirror{
  def renderSelector(selector: Seq[Mirror.Segment]) = {
    val Mirror.Segment.Label(head) :: rest = selector.toList
    val stringSegments = rest.map{
      case Mirror.Segment.Label(s) => "." + s
      case Mirror.Segment.Cross(vs) => "[" + vs.mkString(",") + "]"
    }
    head + stringSegments.mkString
  }

  sealed trait Segment
  object Segment{
    case class Label(value: String) extends Segment
    case class Cross(value: Seq[Any]) extends Segment
  }
  def traverse[T, V, R](t: T, hierarchy: Mirror[T, V])
                       (f: (Mirror[T, _], => Seq[Segment]) => Seq[R]): Seq[R] = {
    def rec[C](segmentsRev: List[Segment], h: Mirror[T, C]): Seq[R]= {
      val crossValues = segmentsRev.map{case Segment.Cross(vs) => vs case _ => Nil}
      val self = f(h, segmentsRev)
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


  /**
    * A target after being materialized in a concrete build
    */
  case class LabelledTarget[V](target: Task[V],
                               format: upickle.default.ReadWriter[V],
                               segments: Seq[Segment])

  /**
    * Represents metadata about a particular target, before the target is
    * materialized for a concrete build
    */
  case class TargetPoint[T, V](label: String,
                               format: upickle.default.ReadWriter[V],
                               run: T => Target[V]) {
    def labelled(t: T, segments: Seq[Segment]) = {
      LabelledTarget(run(t), format, segments :+ Segment.Label(label))
    }
  }

  def makeTargetPoint[T, V](label: String, func: T => Target[V])
                (implicit f1: upickle.default.Reader[V],
                 f2: upickle.default.Writer[V]) = {

    val f = upickle.default.ReadWriter(f2.write, f1.read)
    TargetPoint(label, f, func)
  }
}
