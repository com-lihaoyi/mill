package foo
import utest.*
object RandomTestsE extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Sage", 280) }
  }
}
