package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

case class Tree[+A](root: A, children: Seq[Tree[A]] = Nil) {

  def iterator: Iterator[A] = Iterator(root) ++ children.iterator.flatMap(_.iterator)

  def map[B](f: A => B): Tree[B] = Tree(f(root), children.map(_.map(f)))

  def transform[B](f: (A, Seq[Tree[B]]) => Tree[B]): Tree[B] = f(root, children.map(_.transform(f)))
}
object Tree {

  def from[S, A](init: S)(step: S => (A, Seq[S])): Tree[A] = {
    def recurse(state: S): Tree[A] = {
      val (a, states) = step(state)
      Tree(a, states.map(recurse))
    }
    recurse(init)
  }

  implicit def rw[A: ReadWriter]: ReadWriter[Tree[A]] = macroRW
}
