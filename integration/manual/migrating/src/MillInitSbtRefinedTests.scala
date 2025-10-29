package mill.integration
import utest.*
object MillInitSbtRefinedTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/fthomas/refined.git",
      "v0.11.3",
      passingTasks = Seq("__.showModuleDeps"),
      failingTasks = Seq("modules.core.jvm[3.3.4].compile")
    )
  }
}
