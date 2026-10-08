package thing

import utest.*

object ThingTests extends TestSuite {

  val tests = Tests {
    test("thing") {
      val expected =
        """{
          |  "a": 2,
          |  "b": true
          |}""".stripMargin
      assert(Thing.reformatted == expected)
    }
  }

}
