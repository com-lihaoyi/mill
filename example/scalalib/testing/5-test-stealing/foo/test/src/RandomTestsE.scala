package foo
import utest._
object RandomTestsE extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Sage", 28) }
  }
}
