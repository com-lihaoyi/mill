package mill.integration
import utest.*
object InitZioHttpTests extends InitTestSuite {
  def gitUrl = "https://github.com/zio/zio-http"
  def gitRev = "v3.7.4"
  def initArgs = Seq("--mill-jvm-id", "17")
  def tests = Tests {
    test("compile") - assert(
      eval("__:PublishModule.__.compile").isSuccess
    )
    test("test") - assert(
      eval(("-j", 1, "zio-http.jvm._.test")).isSuccess
    )
    test("jmh") - assert(
      eval("zio-http-benchmarks._.listJmhBenchmarks").isSuccess
    )
  }
}
