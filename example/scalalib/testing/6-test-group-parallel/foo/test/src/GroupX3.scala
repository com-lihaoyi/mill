package foo
import utest._
object GroupX3 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Fortuna", 250) }
  }
}
