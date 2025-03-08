package foo
import utest._
object RandomTestsF extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Winter", 12) }
  }
} 