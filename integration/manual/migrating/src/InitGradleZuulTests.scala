package mill.integration
import utest.*
object InitGradleZuulTests extends InitTestSuite(
      "https://github.com/Netflix/zuul",
      "v3.3.6",
      Seq("--gradle-jvm-id", "21", "--mill-jvm-id", "21")
    ) {
  def tests = Tests {
    test("compile") - assert(
      // incremental compile fails on first invocation
      !eval("zuul-core.compile").isSuccess,
      eval("zuul-core.compile").isSuccess
    )
    test("test") - assert(
      eval("zuul-core.test").isSuccess
    )
    test("publish") - assert(
      eval(("zuul-core.publishLocal", "--local-ivy-repo", ".ivy2local")).isSuccess
    )
    test("errorprone") - assert(
      eval(("resolve", "zuul-core.errorProneVersion")).isSuccess
    )
    test("spotless") - assert(
      eval("zuul-core.spotless").isSuccess
    )
    test("issues") {
      test("conflicting dependencies") - assert(
        !eval("zuul-integration-test.resolvedMvnDeps").isSuccess
      )
    }
  }
}
