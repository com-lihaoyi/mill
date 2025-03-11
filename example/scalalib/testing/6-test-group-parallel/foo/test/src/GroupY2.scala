package foo
import utest._
object GroupY2 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Odin", 34) }
  }
}
