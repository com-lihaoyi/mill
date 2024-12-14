package mill.util

import utest.{TestSuite, Tests, test}
import collection.mutable
import SpanningForest.Node
object SpanningForestTests extends TestSuite {

  val tests = Tests {

    test("test") {
      val forest = SpanningForest.apply(
        Array(
          Array(1),
          Array(2),
          Array(3),
          Array(),
          Array(),
        ),
        Set(0)
      )

      val expected =           Node(
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

      assert(forest == expected
      )
    }

  }

}
