package foo
import utest._
object GroupY8 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Khonsu", 53) }
    test("test2") { testGreeting("Leto", 46) }
  }
} 