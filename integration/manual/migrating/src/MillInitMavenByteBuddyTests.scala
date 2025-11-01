package mill.integration
import utest.*
object MillInitMavenByteBuddyTests extends MillInitTestSuite {
  def tests = Tests {
    test("upgraded") - checkImport(
      "https://github.com/raphw/byte-buddy.git",
      "byte-buddy-1.17.8",
      passingTasks = Seq("byte-buddy-dep.compile"),
      failingTasks = Seq(
        // missing custom resources
        "byte-buddy-dep.test",
        // TODO add support for AndroidModule
        "byte-buddy-android-test.compile"
      )
    )
  }
}
