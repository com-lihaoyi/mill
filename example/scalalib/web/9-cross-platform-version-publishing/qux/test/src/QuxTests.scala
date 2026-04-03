package qux
import utest._
object QuxTests extends TestSuite {
  def tests = Tests {
    test("parseJsonGetKeys") {
      val string = """{"i": "am", "cow": "hear", "me": "moo"}"""
      val keys = QuxPlatformSpecific.parseJsonGetKeys(string)
      assert(keys == Set("i", "cow", "me"))
      keys
    }
  }
}
