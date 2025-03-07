package foo
import utest._
object GroupY7 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Horus", 34) }
    test("test2") { testGreeting("Isis", 32) }
    test("test3") { testGreeting("Juno", 33) }
  }
} 