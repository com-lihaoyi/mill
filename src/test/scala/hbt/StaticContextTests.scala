package hbt
import DefCtx.StaticContext
import utest._
class Helper{
  val static = implicitly[StaticContext]
  object Nested {
    val static = implicitly[StaticContext]
  }
  def method = implicitly[StaticContext]
}
object StaticContextTests extends TestSuite{
  val static = implicitly[StaticContext]
  object Nested{
    val static = implicitly[StaticContext]
    def method = implicitly[StaticContext]
    class Helper{
      val static = implicitly[StaticContext]
    }
  }

  def method = implicitly[StaticContext]
  val tests = Tests{
    val helper = new Helper()
    'inObject - assert(static.value)
    'inClass- assert(!helper.static.value)
    'inMethod - assert(!method.value)

    'inObjectObject - assert(Nested.static.value)
    'inObjectClass- assert(!helper.static.value)
    'inObjectMethod- assert(!Nested.method.value)

    'inClassObject - assert(!helper.Nested.static.value)
    'inClassMethod- assert(!helper.method.value)

  }
}
