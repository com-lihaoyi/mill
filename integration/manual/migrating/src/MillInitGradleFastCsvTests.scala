package mill.integration
import utest.*
object MillInitGradleFastCsvTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      gitUrl = "https://github.com/osiegmar/FastCSV.git",
      gitBranch = "v4.0.0",
      initArgs = Seq("--gradle-jvm-id", "24"),
      failingTasks = Seq("lib.compile")
    )
  }
}
