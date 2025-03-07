package foo
import utest._
object RandomTestsB extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Dakota", 18) }
    test("test2") { testGreeting("Ethan", 15) }
    test("test3") { testGreeting("Felix", 12) }
    test("test4") { testGreeting("Gabriel", 16) }
    test("test5") { testGreeting("Harper", 20) }
    test("test6") { testGreeting("Isaac", 14) }
  }
} 