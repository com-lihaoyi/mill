package mill.main.build

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
      implicit val traversal: Tree.Traversal = Tree.Traversal.BreadthFirst

      assert(tree.toSeq == Seq(50, 25, 10, 5, 2, 5, 5, 2))
    }

    test("DepthFirst") {
      implicit val traversal: Tree.Traversal = Tree.Traversal.DepthFirst

      assert(tree.toSeq == Seq(5, 25, 5, 2, 10, 5, 2, 50))
    }
  }
}
