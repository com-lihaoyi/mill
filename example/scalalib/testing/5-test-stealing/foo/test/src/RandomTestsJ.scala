package foo
import utest._
object RandomTestsJ extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Storm", 38) }
    test("test2") { testGreeting("True", 32) }
    test("test3") { testGreeting("Vale", 28) }
  }
} 