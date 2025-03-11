package foo
import utest._
object GroupX2 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Chronos", 35) }
  }
}
