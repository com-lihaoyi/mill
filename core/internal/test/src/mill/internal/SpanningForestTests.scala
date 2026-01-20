package mill.internal

import SpanningForest.Node
import utest.{TestSuite, Tests, test}

import scala.collection.mutable
object SpanningForestTests extends TestSuite {

  val tests = Tests {

    test("test") {
      val forest = SpanningForest.applyInferRoots(
        Array(
          Array(1),
          Array(2),
          Array(3),
          Array[Int](),
          Array[Int]()
        ),
        importantVertices = Set(0, 1, 2, 3)
      )

      val expected = Node(
        mutable.Map(
          0 -> Node(
            mutable.Map(
              1 -> Node(
                mutable.Map(
                  2 -> Node(
                    mutable.Map(
                      3 -> Node(mutable.Map())
                    )
                  )
                )
              )
            )
          )
        )
      )

      assert(forest == expected)
    }

  }

}
