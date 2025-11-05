package mill.integration
import utest.*
object MillInitMavenSpringAiTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/spring-projects/spring-ai.git",
      "v1.0.3",
      envJvmId = "zulu:17",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq(
        // Maven can resolve dep version from BOMs defined in dependencyManagement
        "spring-ai-commons.resolvedMvnDeps"
      )
    )
  }
}
