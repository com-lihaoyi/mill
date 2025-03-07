package foo
import utest._
object RandomTestsF extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Winter", 12) }
    test("test2") { testGreeting("Xander", 10) }
    test("test3") { testGreeting("Yara", 13) }
    test("test4") { testGreeting("Zephyr", 9) }
    test("test5") { testGreeting("Atlas", 11) }
    test("test6") { testGreeting("Blair", 14) }
    test("test7") { testGreeting("Cruz", 10) }
    test("test8") { testGreeting("Dawn", 12) }
    test("test9") { testGreeting("Echo", 8) }
  }
} 