package foo
import utest.*
object RandomTestsA extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Storm", 380) }
  }
}
