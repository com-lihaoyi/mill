package foo
import utest._
object RandomTestsA extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Storm", 38) }
    test("test2") { testGreeting("Bella", 25) }
    test("test3") { testGreeting("Cameron", 32) }
  }
} 