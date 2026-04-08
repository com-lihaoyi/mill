package mill.integration
import utest.*
object InitMavenJodaBeansTests extends InitTestSuite(
      "https://github.com/JodaOrg/joda-beans",
      "v2.11.1",
      Seq("--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("compile").isSuccess
    )
    test("test") - assert(
      eval("test").isSuccess
    )
    test("jacoco") - assert(
      eval("de.tobiasroeser.mill.jacoco.Jacoco/jacocoReportFull").isSuccess
    )
    test("issues") {
      test("javadocGenerated fails") - assert(
        !eval("javadocGenerated").isSuccess
      )
    }
  }
}
