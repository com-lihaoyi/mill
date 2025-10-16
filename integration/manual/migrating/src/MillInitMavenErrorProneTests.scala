package mill.integration
import utest.*
object MillInitMavenErrorProneTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/google/error-prone.git",
      "v2.41.0",
      failingTasks = Seq("annotation.javaHome")
    )
  }
}
