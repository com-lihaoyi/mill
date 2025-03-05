package foo
import utest._
object GroupY6 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Baldur")
      assert(result == "Hello Baldur")
      Thread.sleep(79)
      result
    }
    test("test2") {
      val result = Foo.greet("Calypso")
      assert(result == "Hello Calypso")
      Thread.sleep(28)
      result
    }
    test("test3") {
      val result = Foo.greet("Dagon")
      assert(result == "Hello Dagon")
      Thread.sleep(65)
      result
    }
    test("test4") {
      val result = Foo.greet("Enki")
      assert(result == "Hello Enki")
      Thread.sleep(37)
      result
    }
    test("test5") {
      val result = Foo.greet("Freya")
      assert(result == "Hello Freya")
      Thread.sleep(49)
      result
    }
    test("test6") {
      val result = Foo.greet("Geb")
      assert(result == "Hello Geb")
      Thread.sleep(22)
      result
    }
  }
} 