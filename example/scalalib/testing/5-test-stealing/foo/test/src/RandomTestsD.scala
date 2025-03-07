package foo
import utest._
object RandomTestsD extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Kai", 14) }
    test("test2") { testGreeting("Luna", 11) }
    test("test3") { testGreeting("Mason", 15) }
    test("test4") { testGreeting("Nova", 10) }
    test("test5") { testGreeting("Owen", 13) }
    test("test6") { testGreeting("Piper", 12) }
    test("test7") { testGreeting("Quinn", 14) }
    test("test8") { testGreeting("River", 11) }
  }
} 