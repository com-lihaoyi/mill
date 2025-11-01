package mill.integration
import utest.*
object MillInitMavenErrorProneTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/google/error-prone.git",
      "v2.43.0",
      passingTasks = Seq("annotation.compile"),
      failingTasks = Seq(
        // requires support for javac annotation processors
        "check_api.compile"
      ),
      envJvmId = "zulu:21"
    )
  }
}
