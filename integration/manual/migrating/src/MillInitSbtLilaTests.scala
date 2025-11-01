package mill.integration
import utest.*
object MillInitSbtLilaTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/lichess-org/lila.git",
      "master",
      failingTasks = Seq(
        // references to non-existent test moduleDeps
        ("resolve", "_")
      ),
      envJvmId = "zulu:21"
    )
  }
}
