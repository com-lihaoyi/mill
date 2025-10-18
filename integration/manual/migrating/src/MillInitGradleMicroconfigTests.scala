package mill.integration
import utest.*
object MillInitGradleMicroconfigTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      gitUrl = "https://github.com/microconfig/microconfig.git",
      gitBranch = "v4.9.5",
      initArgs = Seq("--merge"),
      passingTasks = Seq("_.test")
    )
  }
}
