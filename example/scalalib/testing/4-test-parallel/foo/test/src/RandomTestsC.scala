package foo
import utest.*
object RandomTestsC extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Jordan", 950) }
  }
}
