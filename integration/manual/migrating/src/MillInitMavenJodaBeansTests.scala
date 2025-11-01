package mill.integration
import utest.*
object MillInitMavenJodaBeansTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/JodaOrg/joda-beans.git",
      "v2.11.1",
      passingTasks = Seq("test"),
      envJvmId = "zulu:21"
    )
  }
}
