package foo
import utest._
object RandomTestsC extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Jordan", 95) }
  }
} 