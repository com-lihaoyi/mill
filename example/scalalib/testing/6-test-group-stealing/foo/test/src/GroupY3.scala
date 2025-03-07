package foo
import utest._
object GroupY3 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Ra", 21) }
    test("test2") { testGreeting("Saturn", 18) }
    test("test3") { testGreeting("Thor", 19) }
    test("test4") { testGreeting("Ullr", 22) }
    test("test5") { testGreeting("Venus", 17) }
  }
} 