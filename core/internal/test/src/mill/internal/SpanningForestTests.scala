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
        mutable.LinkedHashMap(
          0 -> Node(
            mutable.LinkedHashMap(
              1 -> Node(
                mutable.LinkedHashMap(
                  2 -> Node(
                    mutable.LinkedHashMap(
                      3 -> Node(mutable.LinkedHashMap())
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
