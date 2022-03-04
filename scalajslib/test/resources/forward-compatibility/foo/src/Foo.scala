class MyException extends Exception("MyException")

@annotation.experimental
object Exceptional:
  import language.experimental.saferExceptions
  def foo(): Unit throws MyException = // this requires at least 3.1.x to compile
    throw new MyException

object Foo:
  val numbers = Seq(1, 2, 3)

@main def run() =
  // Just assure that stdlib TASTy files are properly loaded during compilation
  // and accessing stdlib API doesn't crash at runtime.
  // More precise checks like in the corresponding test for the JVM don't seem to be possible
  // because of lack of runtime reflection in ScalaJS.
  val parser = summon[scala.util.CommandLineParser.FromString[Boolean]]
  assert(parser.fromString("true") == true)
