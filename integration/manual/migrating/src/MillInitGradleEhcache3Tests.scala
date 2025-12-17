package mill.integration
import utest.*
object MillInitGradleEhcache3Tests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      gitUrl = "https://github.com/ehcache/ehcache3.git",
      gitBranch = "v3.10.8",
      initArgs = Seq("--gradle-jvm-id", "11"),
      failingTasks = Seq("ehcache-api.compile")
    )
  }
}
