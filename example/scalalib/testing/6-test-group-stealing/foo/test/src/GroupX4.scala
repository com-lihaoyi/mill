package foo
import utest._
object GroupX4 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Janus", 21) }
    test("test2") { testGreeting("Kratos", 18) }
    test("test3") { testGreeting("Luna", 19) }
    test("test4") { testGreeting("Maia", 22) }
    test("test5") { testGreeting("Nyx", 17) }
  }
} 