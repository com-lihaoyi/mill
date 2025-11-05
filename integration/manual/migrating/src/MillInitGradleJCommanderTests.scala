package mill.integration
import utest.*
object MillInitGradleJCommanderTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/cbeust/jcommander.git",
      "3.0",
      passingTasks = Seq("compile"),
      failingTasks = Seq(
        // localCompileClasspath hidden by circular transitive dep
        "test.compile"
      ),
      envJvmId = "zulu:17"
    )
  }
}
