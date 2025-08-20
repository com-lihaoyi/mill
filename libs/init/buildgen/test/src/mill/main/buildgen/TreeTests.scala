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

    test("iterator") {
      assert(tree.iterator.toSeq == Seq(50, 25, 5, 10, 5, 2, 5, 2))
    }

    test("map") {
      assert(tree.map(-_).iterator.toSeq == Seq(-50, -25, -5, -10, -5, -2, -5, -2))
    }

    test("transform") {
      assert(tree.transform((i, is) => Tree(-i, is.reverse)).iterator.toSeq == Seq(
        -50, -2, -5, -10, -2, -5, -25, -5
      ))
    }
  }
}
