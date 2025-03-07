package foo
import utest._
object GroupY2 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Odin", 34) }
    test("test2") { testGreeting("Pluto", 32) }
    test("test3") { testGreeting("Quetzal", 33) }
  }
} 