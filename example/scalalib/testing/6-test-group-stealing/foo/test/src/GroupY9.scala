package foo
import utest._
object GroupY9 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Minerva", 21) }
    test("test2") { testGreeting("Nike", 18) }
    test("test3") { testGreeting("Osiris", 19) }
    test("test4") { testGreeting("Pan", 22) }
    test("test5") { testGreeting("Qebui", 17) }
  }
} 