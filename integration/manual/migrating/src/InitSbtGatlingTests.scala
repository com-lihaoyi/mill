package mill.integration
import utest.*
object InitSbtGatlingTests extends InitTestSuite(
      "https://github.com/gatling/gatling",
      "v3.14.9",
      Seq("--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("__.compile").isSuccess
    )
    test("test") - assert(
      eval("gatling-core.test").isSuccess
    )
    test("publishLocal") - assert(
      eval(("gatling-core.publishLocal", "--local-ivy-repo", ".ivy2local")).isSuccess
    )
    test("jmh") - assert(
      eval(("gatling-benchmarks.runJmh", "-l")).isSuccess
    )
    test("scalafix") - assert(
      eval(("gatling-core.fix", "--rules", "NoAutoTupling")).isSuccess
    )
    test("issues") {
      test("missing generated resources") - assert(
        !eval((
          "gatling-charts.test.testOnly",
          "io.gatling.charts.result.reader.LogFileReaderSpec"
        )).isSuccess
      )
      test("Scaladoc generation fails for module with Java only sources") - assert(
        !eval("gatling-netty-util.scalaDocGenerated").isSuccess
      )
      test("ScalafmtModule requires config file") - assert(
        !eval("gatling-core.checkFormat").isSuccess
      )
    }
  }
}
