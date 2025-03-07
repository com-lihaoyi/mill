package foo
import utest._
object RandomTestsG extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Finn", 45) }
    test("test2") { testGreeting("Gray", 52) }
  }
} 