package foo
import utest._
object FooTests extends TestSuite {
  def tests = Tests {
    test("parseJsonGetKeys") {
      val string = """{"i": "am", "cow": "hear", "me": "moo}"""
      val keys = FooPlatformSpecific.parseJsonGetKeys(string)
      assert(keys == Set("i", "cow", "me"))
      keys
    }
  }
}
