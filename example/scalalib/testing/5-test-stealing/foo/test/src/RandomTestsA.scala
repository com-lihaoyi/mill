package foo
import utest._
object RandomTestsA extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Storm", 38) }
  }
} 