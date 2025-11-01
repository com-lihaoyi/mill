package mill.integration
import utest.*
object MillInitGradleSpringFrameworkTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/spring-projects/spring-framework.git",
      "v6.2.12",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq(
        // TODO add support for BomModule
        "resolvedMvnDeps"
      ),
      envJvmId = "zulu:24"
    )
  }
}
