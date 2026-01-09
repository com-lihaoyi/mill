package foo

import utest._

object FooTests extends TestSuite {
  val tests = Tests {
    test("hello") {
      assert(Foo.hello() == "Hello")
    }
    test("world") {
      assert(Foo.world() == "World")
    }
  }
}
