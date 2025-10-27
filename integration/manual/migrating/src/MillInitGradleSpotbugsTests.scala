package mill.integration
import utest.*
object MillInitGradleSpotbugsTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      gitUrl = "https://github.com/spotbugs/spotbugs.git",
      gitBranch = "4.9.4",
      initArgs = Seq("--gradle-jvm-id", "17"),
      passingTasks = Seq("spotbugs.compile"),
      failingTasks = Seq("eclipsePlugin-test.resolvedMvnDeps")
    )
  }
}
