package bar
import utest._
object BarVersionSpecificTests extends TestSuite {
  def tests = Tests {
    test("test") {
      assert(BarVersionspecific.text().contains("3.x"))
    }
  }
}
