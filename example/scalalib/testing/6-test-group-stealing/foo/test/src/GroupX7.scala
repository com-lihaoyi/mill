package foo
import utest._
object GroupX7 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Selene", 52) }
    test("test2") { testGreeting("Themis", 47) }
  }
} 