package mill.integration
import utest.*
object MillInitGradleJCommanderTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      gitUrl = "https://github.com/cbeust/jcommander.git",
      gitBranch = "2.0",
      initArgs = Seq("--gradle-jvm-id", "11"),
      failingTasks = Seq("test.compile")
    )
  }
}
