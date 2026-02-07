package mill.integration
import utest.*
object InitSbtFs2Tests extends InitTestSuite(
      "https://github.com/typelevel/fs2",
      "v3.12.2",
      Seq("--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("(__:PublishModule).__.compile").isSuccess
    )
    test("test") - assert(
      eval("core.js._.test").isSuccess,
      eval("core.jvm._.test").isSuccess
    )
    test("publishLocal") - assert(
      eval(("core.__.publishLocal", "--local-ivy-repo", ".ivy2local")).isSuccess
    )
    test("mima") - assert(
      eval(("show", "core.__.mimaBinaryIssueFilters")).isSuccess
    )
    test("scalafmt") - assert(
      eval("core.__.checkFormat").isSuccess
    )
    test("issues") {
      test("ScalaNative version not supported") - assert(
        !eval("core.native.2_13_16.test.scalaNativeWorkerClasspath").isSuccess
      )
      test("ScalaJS resources not found at runtime") - assert(
        !eval(("io.js.2_13_16.test.testOnly", "fs2.io.net.tls.TLSSocketSuite")).isSuccess
      )
      test("Java 9+ API not available with scalacOptions --release 8") - assert(
        !eval("benchmark.2_13_16.compile").isSuccess
      )
    }
  }
}
