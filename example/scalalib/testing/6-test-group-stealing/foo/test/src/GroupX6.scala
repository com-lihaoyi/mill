package foo
import utest._
object GroupX6 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Perseus", 34) }
    test("test2") { testGreeting("Quirinus", 32) }
    test("test3") { testGreeting("Rhea", 33) }
  }
} 