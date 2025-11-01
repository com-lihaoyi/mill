package mill.integration
import utest.*
object MillInitSbtAirstreamTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/raquo/Airstream.git",
      "v17.2.1",
      passingTasks = Seq("_.compile"),
      failingTasks = Seq(
        // TODO add support for jsEnvConfig
        ("[2.13.16].test.testOnly", "com.raquo.airstream.web.WebStorageVarSpec")
      ),
      envJvmId = "zulu:17"
    )
  }
}
