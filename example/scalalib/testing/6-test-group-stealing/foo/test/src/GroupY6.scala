package foo
import utest._
object GroupY6 extends RandomTestsUtils {
  def tests = Tests {
    test("test1") { testGreeting("Baldur", 17) }
    test("test2") { testGreeting("Calypso", 15) }
    test("test3") { testGreeting("Dagon", 18) }
    test("test4") { testGreeting("Enki", 16) }
    test("test5") { testGreeting("Freya", 17) }
    test("test6") { testGreeting("Geb", 15) }
  }
} 