package foo
import utest._
object RandomTestsE extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Sage", 28) }
    test("test2") { testGreeting("Talia", 22) }
    test("test3") { testGreeting("Urban", 25) }
    test("test4") { testGreeting("Violet", 20) }
  }
} 