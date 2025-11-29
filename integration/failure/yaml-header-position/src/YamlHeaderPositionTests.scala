package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlHeaderPositionTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("version")

      assert(res.isSuccess == false)
      val expectedError =
        "Invalid YAML header comment at build.mill:2: //| mill-version: 1.0.0-RC1\n" +
          "YAML header comments can only occur at the start of the file"
      assert(res.err.contains(expectedError))
    }
  }
}
