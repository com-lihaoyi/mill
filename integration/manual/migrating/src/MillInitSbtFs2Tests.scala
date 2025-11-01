package mill.integration
import utest.*
object MillInitSbtFs2Tests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/typelevel/fs2.git",
      "v3.12.2",
      passingTasks = Seq(
        ("core.js[2.13.16].test.testOnly", "fs2.hashing.HashingSuite"),
        ("core.jvm[3.3.5].test.testOnly", "fs2.hashing.HashingSuite")
      ),
      failingTasks = Seq(
        // legacy scalaNativeVersion not supported
        "core.native[2.12.20].test.scalaNativeWorkerClasspath",
        // SBT compile excludes --release 8
        "benchmark[3.3.5].compile"
      ),
      envJvmId = "zulu:17"
    )
  }
}
