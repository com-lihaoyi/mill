package foo
import utest._
object GroupX4 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Janus", 210) }
  }
}
