package foo
import utest._
object RandomTestsH extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Haven", 22) }
  }
} 