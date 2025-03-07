package foo
import utest._
object GroupX9 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Ymir", 17) }
    test("test2") { testGreeting("Zeus", 15) }
    test("test3") { testGreeting("Atlas", 18) }
    test("test4") { testGreeting("Balder", 16) }
    test("test5") { testGreeting("Ceres", 17) }
    test("test6") { testGreeting("Diana", 15) }
  }
} 