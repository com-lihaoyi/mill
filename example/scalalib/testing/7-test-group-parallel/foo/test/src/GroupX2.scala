package foo
import utest.*
object GroupX2 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Chronos", 350) }
  }
}
