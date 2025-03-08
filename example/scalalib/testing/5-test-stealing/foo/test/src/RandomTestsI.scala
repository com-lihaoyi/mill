package foo
import utest._
object RandomTestsI extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Mars", 16) }
  }
} 