package mill.integration
import utest.*
object InitSbtAirstreamTests extends InitTestSuite(
      "https://github.com/raquo/Airstream",
      "v17.2.1",
      Seq("--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("__.compile").isSuccess
    )
    test("publishLocal") - assert(
      eval(("_.publishLocal", "--local-ivy-repo", ".ivy2local")).isSuccess
    )
    test("mima") - assert(
      eval("_.mimaReportBinaryIssues").isSuccess
    )
    test("issues") {
      test("requires support for jsEnv") - assert(
        !eval(("_.test.testOnly", "com.raquo.airstream.web.WebStorageVarSpec")).isSuccess
      )
      test("scalafmtConfig file does not specify the scalafmt version to use") - assert(
        !eval("_.checkFormat").isSuccess
      )
    }
  }
}
