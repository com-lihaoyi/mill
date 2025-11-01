package mill.integration
import utest.*
object MillInitSbtEnumeratumTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/lloydmeta/enumeratum.git",
      "enumeratum-1.9.0",
      passingTasks = Seq("macros.jvm[3.3.5].compile"),
      failingTasks = Seq(
        // requires support for custom source dir for Scala 2.x
        "macros.jvm[_].compile"
      )
    )
  }
}
