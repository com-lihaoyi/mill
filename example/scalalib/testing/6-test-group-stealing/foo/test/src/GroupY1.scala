package foo
import utest._
object GroupY1 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Hades", 15) }
  }
} 