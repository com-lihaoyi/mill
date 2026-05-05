package foo
import utest.*
object GroupY1 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Hades", 150) }
  }
}
