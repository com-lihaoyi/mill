package foo
import utest.*
object RandomTestsD extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Kai", 140) }
  }
}
