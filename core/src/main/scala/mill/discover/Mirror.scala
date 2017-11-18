package mill.discover

import mill.define.Task
import mill.discover.Router.EntryPoint

import scala.language.experimental.macros


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


  case class LabelledTarget[T](target: Task[T],
                               format: upickle.default.ReadWriter[T],
                               segments: Seq[String])

  case class TargetPoint[T, V](label: String,
                               format: upickle.default.ReadWriter[V],
                               run: T => Task[V]) {
    def labelled(t: T, segments: Seq[String]) = LabelledTarget(run(t), format, segments :+ label)
  }

  def makeTargetPoint[T, V](label: String, func: T => Task[V])
                (implicit f: upickle.default.ReadWriter[V]) = {
    TargetPoint(label, f, func)
  }
}
