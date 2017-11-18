package mill.discover

import mill.define.{Target, Task}
import mill.discover.Router.EntryPoint

import scala.language.experimental.macros

/**
  * Metadata about a build that is extracted & materialized at compile-time,
  * letting us use it at run-time without needing to use the heavy weight
  * scala-reflect library.
  *
  * Note that [[Mirror]] allows you to store and inspect metadata of a type
  * [[T]] even without a concrete instance of [[T]] itself.
  */
case class Mirror[-T, V](node: T => V,
                         commands: Seq[EntryPoint[V]],
                         targets: Seq[Mirror.TargetPoint[V, _]],
                         children: List[(String, Mirror[T, _])]){
  def labelled(obj: T, p: Seq[String]) = {
    targets.map(t => t.labelled(node(obj), p))
  }
}

object Mirror{
  def traverse[T, V, R](hierarchy: Mirror[T, V])
                       (f: (Mirror[T, _], => Seq[String]) => Seq[R]): Seq[R] = {
    def rec[C](segmentsRev: List[String], h: Mirror[T, C]): Seq[R]= {
      val self = f(h, segmentsRev)
      self ++ h.children.flatMap{case (label, c) => rec(label :: segmentsRev, c)}
    }
    rec(Nil, hierarchy)
  }

  /**
    * A target after being materialized in a concrete build
    */
  case class LabelledTarget[V](target: Task[V],
                               format: upickle.default.ReadWriter[V],
                               segments: Seq[String])

  /**
    * Represents metadata about a particular target, before the target is
    * materialized for a concrete build
    */
  case class TargetPoint[T, V](label: String,
                               format: upickle.default.ReadWriter[V],
                               run: T => Target[V]) {
    def labelled(t: T, segments: Seq[String]) = LabelledTarget(run(t), format, segments :+ label)
  }

  def makeTargetPoint[T, V](label: String, func: T => Target[V])
                (implicit f: upickle.default.ReadWriter[V]) = {
    TargetPoint(label, f, func)
  }
}
