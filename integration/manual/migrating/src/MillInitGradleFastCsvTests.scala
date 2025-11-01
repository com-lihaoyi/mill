package mill.integration
import utest.*
object MillInitGradleFastCsvTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/osiegmar/FastCSV.git",
      "v4.1.0",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq(
        // Gradle auto-configures -proc:none
        "lib.compile"
      ),
      envJvmId = "zulu:24" // 25 is not available in Coursier index
    )
  }
}
