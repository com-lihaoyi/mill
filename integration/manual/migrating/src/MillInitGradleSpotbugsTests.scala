package mill.integration
import utest.*
object MillInitGradleSpotbugsTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/spotbugs/spotbugs.git",
      "4.9.8",
      passingTasks = Seq("spotbugs.compile"),
      failingTasks = Seq(
        // TODO add support for completely overriding javacOptions
        "test-harness-jupiter.test.compile"
      ),
      envJvmId = "zulu:21"
    )
  }
}
