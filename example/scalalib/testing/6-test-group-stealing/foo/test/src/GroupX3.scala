package foo
import utest._
object GroupX3 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Fortuna", 25) }
    test("test2") { testGreeting("Gaia", 22) }
    test("test3") { testGreeting("Helios", 28) }
    test("test4") { testGreeting("Iris", 24) }
  }
}