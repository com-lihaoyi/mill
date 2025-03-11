package foo
import utest._
object GroupY10 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Rama", 25) }
  }
}
