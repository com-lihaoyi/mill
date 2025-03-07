package foo
import utest._
object GroupX2 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Chronos", 35) }
    test("test2") { testGreeting("Demeter", 31) }
    test("test3") { testGreeting("Eos", 32) }
  }
}