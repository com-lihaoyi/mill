package mill.integration
import utest.*
object MillInitSbtNscalaTimeTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/nscala-time/nscala-time.git",
      "releases/3.0.0",
      passingTasks = Seq("_.test")
    )
  }
}
