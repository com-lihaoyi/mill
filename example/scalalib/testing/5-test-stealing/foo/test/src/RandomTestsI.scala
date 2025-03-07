package foo
import utest._
object RandomTestsI extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Mars", 16) }
    test("test2") { testGreeting("North", 12) }
    test("test3") { testGreeting("Onyx", 14) }
    test("test4") { testGreeting("Phoenix", 15) }
    test("test5") { testGreeting("Quest", 13) }
    test("test6") { testGreeting("Rain", 11) }
    test("test7") { testGreeting("Sky", 17) }
  }
} 