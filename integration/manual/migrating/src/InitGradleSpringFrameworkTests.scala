package mill.integration
import utest.*
object InitGradleSpringFrameworkTests extends InitTestSuite(
      "https://github.com/spring-projects/spring-framework",
      "v7.0.3",
      Seq("--gradle-jvm-id", "25", "--mill-jvm-id", "25")
    ) {
  def tests = Tests {
    test("checkstyle") - assert(
      eval(("resolve", "spring-core.checkstyleMvnDeps")).isSuccess
    )
    test("issues") {
      test("missing deps from custom configuration") - assert(
        !eval("spring-core.compile").isSuccess
      )
      test("incompatible checkstyle configuration") - assert(
        !eval("spring-core.checkstyle").isSuccess
      )
    }
  }
}
