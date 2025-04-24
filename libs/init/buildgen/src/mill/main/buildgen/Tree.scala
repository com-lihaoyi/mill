package mill.main.buildgen

import geny.Generator

/**
 * A recursive data structure that defines parent-child relationships between nodes.
 *
 * @param node the root node of this tree
 * @param children the child subtrees of this tree
 */
@mill.api.experimental
case class Tree[+Node](node: Node, children: Seq[Tree[Node]] = Seq.empty) {

  def map[Out](f: Node => Out): Tree[Out] =
    transform[Out]((node, children) => Tree(f(node), children.iterator.toSeq))

  def nodes(traversal: Tree.Traversal = Tree.Traversal.DepthFirst): Generator[Node] =
    subtrees(traversal).map(_.node)

  def subtrees(traversal: Tree.Traversal): Generator[Tree[Node]] =
    traversal.subtrees(this)

  def transform[Out](f: (Node, IterableOnce[Tree[Out]]) => Tree[Out]): Tree[Out] = {
    def recurse(tree: Tree[Node]): Tree[Out] =
      f(tree.node, tree.children.iterator.map(recurse))

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

    def subtrees[Node](root: Tree[Node]): Generator[Tree[Node]]
  }
  object Traversal {

    object BreadthFirst extends Traversal {

      def subtrees[Node](root: Tree[Node]): Generator[Tree[Node]] = handleItem => {
        @annotation.tailrec
        def recurse(level: Seq[Tree[Node]]): Generator.Action = {
          var last: Generator.Action = Generator.Continue
          var index = 0
          while (last == Generator.Continue && index < level.length) {
            last = handleItem(level(index))
            index += 1
          }
          val nextLevel = level.flatMap(_.children)
          if (last == Generator.Continue && nextLevel.nonEmpty) recurse(nextLevel) else last
        }

        recurse(Seq(root))
      }
    }

    object DepthFirst extends Traversal {

      def subtrees[Node](root: Tree[Node]): Generator[Tree[Node]] = handleItem => {
        def recurse(tree: Tree[Node]): Generator.Action = {
          var last: Generator.Action = Generator.Continue
          var index = 0
          while (last == Generator.Continue && index < tree.children.length) {
            last = recurse(tree.children(index))
            index += 1
          }
          if (last == Generator.Continue) handleItem(tree) else last
        }

        recurse(root)
      }
    }
  }
}
