package mill.integration
import utest.*
object MillInitGradleMockitoTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/mockito/mockito.git",
      "v5.20.0",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq(
        // Gradle autoconfigures --module-path for deps
        "mockito-core.compile"
      ),
      envJvmId = "zulu:17"
    )
  }
}
