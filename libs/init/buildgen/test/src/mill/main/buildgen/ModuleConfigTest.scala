package mill.main.buildgen

import utest.*

object ModuleConfigTest extends TestSuite {

  def tests = Tests {
    test - {
      Seq(ScalaModuleConfig(scalacOptions =
        Seq("-unchecked", "-deprecation", "-language:_", "-encoding", "UTF-8", "-Ywarn-unused")
      ))
    }
  }
}
