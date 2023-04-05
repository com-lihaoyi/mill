package mill.runner.codesig
import utest._
object CodeSigTests extends TestSuite{
  val tests = Tests{
    println("Hello World" + CodeSig.x)
  }
}
