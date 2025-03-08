package foo
import utest._
object GroupX9 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Ymir", 17) }
  }
} 