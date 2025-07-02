package foo
import utest.*
object GroupY2 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Odin", 340) }
  }
}
