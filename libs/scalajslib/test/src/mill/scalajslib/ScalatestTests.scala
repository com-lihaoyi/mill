package mill.scalajslib

import mill.javalib.api.JvmWorkerUtil
import utest.*

object ScalatestTests extends TestSuite {
  import CompileLinkTests.*
  import UtestTests.*
  def tests: Tests = Tests {

    test("scalatest") {
      testAllMatrix(
        (scala, scalaJS) => checkScalaTest(scala, scalaJS, cached = false),
        skipScala = JvmWorkerUtil.isScala3
      )
    }

    test("scalatestCached") {
      testAllMatrix(
        (scala, scalaJS) => checkScalaTest(scala, scalaJS, cached = true),
        skipScala = JvmWorkerUtil.isScala3
      )
    }

  }
}
