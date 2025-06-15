package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object InvalidBuildHeaderNoSpaceTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("version")

      assert(res.isSuccess == false)
      val expectedError =
        "Invalid YAML header comment at build.mill:0: //|mill-version: 1.0.0-RC1\n" +
          "YAML header comments must start with `//| ` with a newline separating the `|` and the data on the right"
      assert(res.err.contains(expectedError))
    }
  }
}
