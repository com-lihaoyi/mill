package mill.integration

import utest.*

object MillInitSbtScryptoTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/input-output-hk/scrypto.git",
      "v3.1.0",
      failingTasks = Seq("js[2.13.16].compile")
    )
  }
}
