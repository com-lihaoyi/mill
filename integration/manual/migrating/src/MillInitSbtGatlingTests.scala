package mill.integration
import utest.*
object MillInitSbtGatlingTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/gatling/gatling.git",
      "v3.14.3",
      failingTasks = Seq("gatling-http-client.test.compile")
    )
  }
}
