package mill.integration
import utest.*
object InitGradleMicroconfigTests extends InitTestSuite(
      "https://github.com/microconfig/microconfig",
      "v4.9.5",
      Seq("--gradle-jvm-id", "17", "--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("_.compile").isSuccess
    )
    test("test") - assert(
      eval("_.test").isSuccess
    )
    test("publish") - assert(
      eval(("_.publishLocal", "--local-ivy-repo", ".ivy2local")).isSuccess
    )
    test("jacoco") - assert(
      eval("de.tobiasroeser.mill.jacoco.Jacoco/jacocoReportFull").isSuccess
    )
  }
}
