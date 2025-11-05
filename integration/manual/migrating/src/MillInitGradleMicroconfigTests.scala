package mill.integration
import utest.*
object MillInitGradleMicroconfigTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/microconfig/microconfig.git",
      "v4.9.5",
      passingTasks = Seq("_.test"),
      envJvmId = "zulu:17"
    )
  }
}
