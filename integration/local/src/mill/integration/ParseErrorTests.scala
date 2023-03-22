
package mill.integration

import utest._

class ParseErrorTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("parse-error", fork, clientServer) {
  val tests = Tests {
    initWorkspace()


    test {
      val (res, out, err) = evalStdout("foo.scalaVersion")
      assert(res == false)
      val errorString = err.mkString("\n")

      assert(
        errorString.contains(
          """bar.sc:4:20 expected ")"
            |println(doesntExist})
            |                   ^""".stripMargin
        )
      )
      assert(
        errorString.contains(
          """qux.sc:3:31 expected ")"
            |System.out.println(doesntExist
            |                              ^""".stripMargin
        )
      )

    }
  }
}
