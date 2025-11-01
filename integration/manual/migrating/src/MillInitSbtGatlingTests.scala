package mill.integration
import utest.*
object MillInitSbtGatlingTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/gatling/gatling.git",
      "v3.14.7",
      passingTasks = Seq("__.compile"),
      failingTasks = Seq(
        // missing generated resource
        "gatling-charts.test"
      ),
      envJvmId = "zulu:24" // 25 is not available in Coursier index
    )
  }
}
