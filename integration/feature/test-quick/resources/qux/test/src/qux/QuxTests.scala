package qux

import utest._

object QuxTests extends TestSuite {
  val tests = Tests {
    test("hello") {
      assert(Qux.hello() == "Hello")
    }
    test("world") {
      assert(Qux.world() == "World")
    }
  }
}
