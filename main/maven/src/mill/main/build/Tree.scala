package mill.main.build

import mill.main.build.Tree.Traversal

import scala.collection.compat.Factory

/**
 * A recursive data structure that defines parent-child relationships between nodes.
 *
 * @param node the root node of this tree
 * @param subtrees the child subtrees of this tree
 */
@mill.api.experimental
case class Tree[+Node](node: Node, subtrees: Seq[Tree[Node]] = Seq.empty) {

  def fold[S](initial: S)(operator: (S, Node) => S)(implicit T: Traversal): S = {
    var s = initial
    T.foreach(this)(tree => s = operator(s, tree.node))
    s
  }

  def map[Out](f: Node => Out): Tree[Out] =
    transform[Out]((node, subtrees) => Tree(f(node), subtrees.iterator.toSeq))

  def to[F](implicit F: Factory[Node, F], T: Traversal): F =
    fold(F.newBuilder)(_ += _).result()

  def toSeq(implicit T: Traversal): Seq[Node] =
    to

  def transform[Out](f: (Node, IterableOnce[Tree[Out]]) => Tree[Out]): Tree[Out] = {
    def recurse(tree: Tree[Node]): Tree[Out] =
      f(tree.node, tree.subtrees.iterator.map(recurse))

    recurse(this)
  }
}
@mill.api.experimental
object Tree {

  /** Generates a tree from `start` using the `step` function. */
  def from[Input, Node](start: Input)(step: Input => (Node, IterableOnce[Input])): Tree[Node] = {
    def recurse(input: Input): Tree[Node] = {
      val (node, next) = step(input)
      Tree(node, next.iterator.map(recurse).toSeq)
    }

    recurse(start)
  }

  sealed trait Traversal {

    def foreach[Node](root: Tree[Node])(visit: Tree[Node] => Unit): Unit
  }
  object Traversal {

    implicit def traversal: Traversal = DepthFirst

    object BreadthFirst extends Traversal {

      def foreach[Node](root: Tree[Node])(visit: Tree[Node] => Unit): Unit = {
        @annotation.tailrec
        def recurse(level: Seq[Tree[Node]]): Unit = {
          if (level.nonEmpty) {
            level.foreach(visit)
            recurse(level.flatMap(_.subtrees))
          }
        }

        recurse(Seq(root))
      }
    }

    object DepthFirst extends Traversal {

      def foreach[Node](root: Tree[Node])(visit: Tree[Node] => Unit): Unit = {
        def recurse(tree: Tree[Node]): Unit = {
          tree.subtrees.foreach(recurse)
          visit(tree)
        }

        recurse(root)
      }
    }
  }
}
