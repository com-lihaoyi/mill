package mill.scalajslib

import mill.javalib.api.JvmWorkerUtil
import utest.*

object Junit4Tests extends TestSuite {
  import CompileLinkTests.*
  import UtestTests.*
  def tests: Tests = Tests {

    test("junit4") {
      testAllMatrix((scala, scalaJS) => checkJunit4(scala, scalaJS, cached = false))
    }

    test("junit4Cached") {
      testAllMatrix((scala, scalaJS) => checkJunit4(scala, scalaJS, cached = true))
    }

  }
}
