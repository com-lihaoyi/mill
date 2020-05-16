package foo
import utest._
object ExampleTests extends TestSuite{
  def tests = Tests{
    test("hello"){
      val result = Example.hello()
      assert(result == "Hello World")
      result
    }
  }
}
