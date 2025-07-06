package foo
import utest.*
object GroupX3 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Fortuna", 250) }
  }
}
