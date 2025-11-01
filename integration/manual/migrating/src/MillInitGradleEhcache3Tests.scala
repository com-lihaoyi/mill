package mill.integration
import utest.*
object MillInitGradleEhcache3Tests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/ehcache/ehcache3.git",
      "v3.11.1",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq(
        // Gradle auto-configures -proc:none
        "ehcache-api.compile"
      ),
      envJvmId = "zulu:17"
    )
  }
}
