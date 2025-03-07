package foo
import utest._
object GroupY5 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Xiuhtecuhtli", 26) }
    test("test2") { testGreeting("Yarilo", 22) }
    test("test3") { testGreeting("Zephyrus", 23) }
    test("test4") { testGreeting("Aegir", 25) }
  }
} 