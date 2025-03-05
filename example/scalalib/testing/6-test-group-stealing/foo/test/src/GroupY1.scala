package foo
import utest._
object GroupY1 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Hades")
      assert(result == "Hello Hades")
      Thread.sleep(64)
      result
    }
    test("test2") {
      val result = Foo.greet("Indra")
      assert(result == "Hello Indra")
      Thread.sleep(35)
      result
    }
    test("test3") {
      val result = Foo.greet("Jupiter")
      assert(result == "Hello Jupiter")
      Thread.sleep(78)
      result
    }
    test("test4") {
      val result = Foo.greet("Kali")
      assert(result == "Hello Kali")
      Thread.sleep(26)
      result
    }
    test("test5") {
      val result = Foo.greet("Loki")
      assert(result == "Hello Loki")
      Thread.sleep(57)
      result
    }
    test("test6") {
      val result = Foo.greet("Mars")
      assert(result == "Hello Mars")
      Thread.sleep(42)
      result
    }
    test("test7") {
      val result = Foo.greet("Neptune")
      assert(result == "Hello Neptune")
      Thread.sleep(69)
      result
    }
  }
} 