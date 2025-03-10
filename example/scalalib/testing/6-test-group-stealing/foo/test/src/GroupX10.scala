package foo
import utest._
object GroupX10 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Echo", 52) }
  }
}
