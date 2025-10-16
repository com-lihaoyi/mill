package mill.integration
import utest.*
object MillInitMavenCheckstyleTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/checkstyle/checkstyle.git",
      "checkstyle-11.0.0",
      failingTasks = Seq("compile")
    )
  }
}
