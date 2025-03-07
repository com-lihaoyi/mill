package foo
import utest._
object RandomTestsH extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Haven", 22) }
    test("test2") { testGreeting("Iris", 18) }
    test("test3") { testGreeting("Jazz", 20) }
    test("test4") { testGreeting("Kit", 15) }
    test("test5") { testGreeting("Lake", 21) }
  }
} 