package mill.integration
import utest.*
object MillInitSbtEnumeratumTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/lloydmeta/enumeratum.git",
      "enumeratum-1.9.0",
      passingTasks = Seq("enumeratum-core.jvm[2.13.16].compile"),
      failingTasks = Seq("macros.jvm[2.13.16].compile")
    )
  }
}
