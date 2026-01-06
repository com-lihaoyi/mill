package bar
import utest._
object BarVersionSpecificTests extends TestSuite {
  def tests = Tests {
    test("test") {
      assert(BarVersionSpecific.text().contains("2.x"))
    }
  }
}
