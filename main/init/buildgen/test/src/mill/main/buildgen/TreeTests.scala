package mill.main.buildgen

import utest.*

object TreeTests extends TestSuite {

  def tests: Tests = Tests {

    val tree: Tree[Int] =
      Tree(
        50,
        Seq(
          Tree(
            25,
            Seq(
              Tree(5)
            )
          ),
          Tree(
            10,
            Seq(
              Tree(5),
              Tree(2)
            )
          ),
          Tree(5),
          Tree(2)
        )
      )

    test("BreadthFirst") {
      assert(tree.nodes(Tree.Traversal.BreadthFirst).toSeq == Seq(50, 25, 10, 5, 2, 5, 5, 2))
    }

    test("DepthFirst") {
      assert(tree.nodes(Tree.Traversal.DepthFirst).toSeq == Seq(5, 25, 5, 2, 10, 5, 2, 50))
    }
  }
}
