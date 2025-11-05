package mill.integration
import utest.*
object MillInitMavenCheckstyleTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/checkstyle/checkstyle.git",
      "checkstyle-12.1.1",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq(
        // missing generated sources
        "compile"
      ),
      envJvmId = "zulu:17"
    )
  }
}
