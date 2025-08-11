package example

class MySuite extends munit.FunSuite {
  test("hello") {
    val obtained = 42
    val expected = 43
    assertEquals(obtained, expected)
  }
}
