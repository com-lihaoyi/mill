package foo
import utest._
object RandomTestsD extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Kai", 14) }
  }
}
