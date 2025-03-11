package foo
import utest._
object GroupX1 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Aether", 55) }
  }
}
