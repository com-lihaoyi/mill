package mill.integration
import utest.*
object MillInitGradleMockitoTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      gitUrl = "https://github.com/mockito/mockito.git",
      gitBranch = "v5.19.0",
      initArgs = Seq("--gradle-jvm-id", "17"),
      failingTasks = Seq("mockito-core.compile")
    )
  }
}
