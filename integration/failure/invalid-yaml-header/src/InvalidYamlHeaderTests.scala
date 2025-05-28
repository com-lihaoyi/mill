package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object InvalidYamlHeaderTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("version")

      assert(res.isSuccess == false)
      val expectedError =
        "Invalid YAML header comment detected on line 2: //| mill-version: 1.0.0-RC1\n" +
          "YAML header comments can only occur at the start of the file"
      assert(res.err.contains(expectedError))
    }
  }
}
