package mill.integration
import utest.*
object MillInitGradleSpringFrameworkTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      gitUrl = "https://github.com/spring-projects/spring-framework.git",
      gitBranch = "v6.2.11",
      initArgs = Seq("--gradle-jvm-id", "24"),
      failingTasks = Seq("spring-instrument.resolvedMvnDeps")
    )
  }
}
