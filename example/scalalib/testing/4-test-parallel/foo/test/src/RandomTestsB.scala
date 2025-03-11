package foo
import utest._
object RandomTestsB extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Dakota", 18) }
  }
}
