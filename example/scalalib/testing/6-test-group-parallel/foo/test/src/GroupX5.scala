package foo
import utest.*
object GroupX5 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Orion", 950) }
  }
}
