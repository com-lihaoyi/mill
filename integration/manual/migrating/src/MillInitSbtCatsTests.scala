package mill.integration
import utest.*
object MillInitSbtCatsTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/typelevel/cats.git",
      "v2.13.0",
      failingTasks = Seq("kernel.jvm[2.13.16].compile")
    )
  }
}
