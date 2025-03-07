package foo
import utest._
object GroupX8 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Uranus", 25) }
    test("test2") { testGreeting("Vesta", 22) }
    test("test3") { testGreeting("Woden", 26) }
    test("test4") { testGreeting("Xipe", 24) }
  }
} 