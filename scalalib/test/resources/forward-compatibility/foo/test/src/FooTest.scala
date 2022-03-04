import utest._

class MyException extends Exception("MyException")

@annotation.experimental
object Exceptional:
  import language.experimental.saferExceptions
  def foo(): Unit throws MyException = // this requires at least 3.1.x to compile
    throw new MyException


class FooTest extends TestSuite:
  val tests = Tests {
    test("foo") {
      assert(Foo.numbers == Seq(1, 2, 3))
    }
  }
