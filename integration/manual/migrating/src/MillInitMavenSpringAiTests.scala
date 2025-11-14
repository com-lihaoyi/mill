package mill.integration
import utest.*
object MillInitMavenSpringAiTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/spring-projects/spring-ai.git",
      "v1.0.3",
      failingTasks = Seq("spring-ai-commons.resolvedMvnDeps")
    )
  }
}
