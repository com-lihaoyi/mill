package test

object Test extends TestSuite {
  val tests = Tests {
    test("test with version range specific code") {
      val result = Compat.mapToSet(List(1, 2, 3, 4), _ + 1)
      val expected = Set(2, 3, 4, 5)
      assert(result == expected)
    }
  }
}
