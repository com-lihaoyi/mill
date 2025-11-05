package mill.integration

import utest.*

object MillInitSbtScryptoTests extends MillInitTestSuite {
  def tests = Tests {
    test("upgraded") - checkImport(
      "https://github.com/input-output-hk/scrypto.git",
      "v3.1.0",
      passingTasks = Seq("jvm[_].test"),
      failingTasks = Seq(
        // requires support for ScalaJSBundlerPlugin
        "js[_].compile"
      )
    )
  }
}
