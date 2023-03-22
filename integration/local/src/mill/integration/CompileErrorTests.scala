
package mill.integration

import utest._

class CompileErrorTests(fork: Boolean, clientServer: Boolean)
  extends IntegrationTestSuite("compile-error", fork, clientServer) {
  def captureOutErr = true
  val tests = Tests {
    initWorkspace()

    test {
      val (res, out, err) = evalStdout("foo.scalaVersion")
      assert(res == false)
      val errorString = err.mkString("\n")
      assert(
        errorString.contains(
          """bar.sc:4:9: not found: value doesntExist
            |[error] println(doesntExist)
            |[error]         ^""".stripMargin
        )
      )
      assert(
        errorString.contains(
          """qux.sc:3:34: type mismatch;
            |[error]  found   : String("0")
            |[error]  required: Int
            |[error] def myOtherMsg = myMsg.substring("0")
            |[error]                                  ^""".stripMargin
        )
      )
      assert(
        errorString.contains(
          """build.sc:8:5: value noSuchMethod is not a member of object build.this.foo
            |[error] foo.noSuchMethod
            |[error]     ^""".stripMargin
        )
      )
    }
  }
}
