package foo
import utest.*
object GroupX1 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Aether", 550) }
  }
}
