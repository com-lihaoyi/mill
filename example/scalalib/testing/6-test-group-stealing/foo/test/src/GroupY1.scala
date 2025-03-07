package foo
import utest._
object GroupY1 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Hades", 15) }
    test("test2") { testGreeting("Indra", 12) }
    test("test3") { testGreeting("Jupiter", 16) }
    test("test4") { testGreeting("Kali", 13) }
    test("test5") { testGreeting("Loki", 14) }
    test("test6") { testGreeting("Mars", 15) }
    test("test7") { testGreeting("Neptune", 13) }
  }
} 