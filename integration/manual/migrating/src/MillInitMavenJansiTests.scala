package mill.integration
import utest.*
object MillInitMavenJansiTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/fusesource/jansi.git",
      "jansi-2.4.2",
      passingTasks = Seq("test")
    )
  }
}
