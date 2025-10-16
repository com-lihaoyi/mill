package mill.integration
import utest.*
object MillInitSbtLilaTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/lichess-org/lila.git",
      "master",
      failingTasks = Seq(("resolve", "_"))
    )
  }
}
