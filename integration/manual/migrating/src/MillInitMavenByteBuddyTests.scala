package mill.integration
import utest.*
object MillInitMavenByteBuddyTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      gitUrl = "https://github.com/raphw/byte-buddy.git",
      gitBranch = "byte-buddy-1.17.7",
      passingTasks = Seq("byte-buddy.compile"),
      failingTasks = Seq("byte-buddy-android-test.compile")
    )
  }
}
