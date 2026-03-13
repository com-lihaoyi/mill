package mill.integration
import utest.*
object InitMavenSpringAiTests extends InitTestSuite(
      "https://github.com/spring-projects/spring-ai",
      "v1.1.2",
      Seq("--mill-jvm-id", "21")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("spring-ai-commons.compile").isSuccess
    )
    test("test") - assert(
      eval("spring-ai-commons.test").isSuccess
    )
    test("checkstyle") - assert(
      eval(("resolve", "spring-ai-commons.checkstyleMvnDeps")).isSuccess
    )
    test("issues") {
      test("missing generated sources") - assert(
        !eval("models.spring-ai-huggingface.compile").isSuccess
      )
      test("unable to resolve embedded checkstyle configuration files") - assert(
        !eval("spring-ai-commons.checkstyle").isSuccess
      )
    }
  }
}
