package foo
import utest.*
object RandomTestsB extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Dakota", 180) }
  }
}
