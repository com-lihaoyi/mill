package mill.scalajslib

import mill.scalalib.api.ZincWorkerUtil
import utest._

object ScalatestTests extends TestSuite {
  import CompileLinkTests._
  import UtestTests._
  def tests: Tests = Tests {

    test("scalatest") {
      testAllMatrix(
        (scala, scalaJS) => checkScalaTest(scala, scalaJS, cached = false),
        skipScala = ZincWorkerUtil.isScala3
      )
    }

    test("scalatestCached") {
      testAllMatrix(
        (scala, scalaJS) => checkScalaTest(scala, scalaJS, cached = true),
        skipScala = ZincWorkerUtil.isScala3
      )
    }

  }
}
