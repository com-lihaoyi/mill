package foo
import utest._
object GroupX8 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Uranus", 25) }
  }
}
