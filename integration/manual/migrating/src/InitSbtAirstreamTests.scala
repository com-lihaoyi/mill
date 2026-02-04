package mill.integration
import utest.*
object InitSbtAirstreamTests extends InitTestSuite(
      "https://github.com/raquo/Airstream",
      "v17.2.1",
      Seq("--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("2_13_16.compile").isSuccess
    )
    test("publishLocal") - assert(
      eval(("2_13_16.publishLocal", "--local-ivy-repo", ".ivy2local")).isSuccess
    )
    test("mima") - assert(
      eval("2_13_16.mimaReportBinaryIssues").isSuccess
    )
    test("issues") {
      test("recursion limit reached on Scala 3") - assert(
        !eval("3_3_3.test.compile").isSuccess
      )
      test("requires support for jsEnv") - assert(
        !eval(("2_13_16.test.testOnly", "com.raquo.airstream.web.WebStorageVarSpec")).isSuccess
      )
      test("scalafmtConfig file does not specify the scalafmt version to use") - assert(
        !eval("2_13_16.checkFormat").isSuccess
      )
    }
  }
}
