package mill.integration
import utest.*
object MillInitSbtCatsTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/typelevel/cats.git",
      "v2.13.0",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq(
        // missing generated sources
        "kernel.jvm[_].compile"
      ),
      envJvmId = "zulu:17"
    )
  }
}
