package mill.integration
import utest.*
object MillInitSbtAirstreamTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/raquo/Airstream.git",
      "v17.2.1",
      failingTasks = Seq(
        ("[2.13.16].test.testOnly", "com.raquo.airstream.web.WebStorageVarSpec")
      )
    )
  }
}
