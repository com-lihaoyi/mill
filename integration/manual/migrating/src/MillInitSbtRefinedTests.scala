package mill.integration
import utest.*
object MillInitSbtRefinedTests extends MillInitTestSuite {
  def tests = Tests {
    test("upgraded") - checkImport(
      "https://github.com/fthomas/refined.git",
      "v0.11.3",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq(
        // custom version ranges not supported
        "modules.core.jvm[_].compile"
      )
    )
  }
}
