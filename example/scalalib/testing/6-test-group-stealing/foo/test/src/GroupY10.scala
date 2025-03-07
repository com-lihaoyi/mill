package foo
import utest._
object GroupY10 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Rama", 25) }
    test("test2") { testGreeting("Set", 22) }
    test("test3") { testGreeting("Thoth", 26) }
    test("test4") { testGreeting("Ukko", 24) }
  }
} 