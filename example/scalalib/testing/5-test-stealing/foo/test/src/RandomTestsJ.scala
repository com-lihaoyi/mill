package foo
import utest._
object RandomTestsJ extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Storm", 38) }
  }
} 