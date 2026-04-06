package mill.integration
import utest.*
object InitSbtZioHttpTests extends InitTestSuite(
      "https://github.com/zio/zio-http",
      "v3.7.4",
      Seq("--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("(__:PublishModule).__.compile").isSuccess
    )
    // Commented because the tasks hang after completion
//    test("test") - assert(
//      eval("zio-http.jvm.2_12_20.test").isSuccess,
//      eval("zio-http.jvm.2_13_16.test").isSuccess,
//      eval("zio-http.jvm.3_3_5.test").isSuccess
//    )
    test("publishLocal") - assert(
      eval(("zio-http.__.publishLocal", "--local-ivy-repo", ".ivy2local")).isSuccess
    )
    test("jmh") - assert(
      eval(("zio-http-benchmarks._.runJmh", "-l")).isSuccess
    )
    test("mima") - assert(
      eval(("show", "zio-http.jvm._.mimaBinaryIssueFilters")).isSuccess
    )
    test("scoverage") - assert(
      eval("zio-http.jvm._.scoverage.consoleReport").isSuccess
    )
    test("scalafix") - assert(
      eval(("zio-http.__.fix", "--rules", "NoAutoTupling")).isSuccess
    )
    test("scalafmt") - assert(
      eval("zio-http.__.reformat").isSuccess
    )
    test("issues") {
      test("fastLinkJSTest linking errors") - assert(
        !eval("zio-http.js.2_12_20.test").isSuccess
      )
      test("requires mapping for sbt-protoc plugin") - assert(
        !eval("sbt-zio-http-grpc-tests.2_12_20.test.compile").isSuccess
      )
    }
  }
}
