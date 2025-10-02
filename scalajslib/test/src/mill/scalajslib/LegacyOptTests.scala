package mill.scalajslib

import utest._

object LegacyOptTests extends TestSuite {
  import CompileLinkTests._

  def tests: Tests = Tests {

    test("fullOpt") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = true)
      )
    }
    test("fastOpt") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = true)
      )
    }
  }

}
