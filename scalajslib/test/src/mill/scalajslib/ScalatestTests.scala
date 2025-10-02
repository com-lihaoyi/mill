package mill.scalajslib

import mill.scalalib.api.JvmWorkerUtil
import utest._

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
