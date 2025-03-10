package foo
import utest._
object RandomTestsG extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Finn", 45) }
  }
}
