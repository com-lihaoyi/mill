package mill.integration
import utest.*
object InitGradleSpectatorTests extends InitTestSuite(
      "https://github.com/Netflix/spectator",
      "v1.9.3",
      Seq("--gradle-jvm-id", "17", "--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("checkstyle") - assert(
      eval("spectator-api.checkstyle").isSuccess
    )
    test("pmd") - assert(
      eval(("resolve", "spectator-api.pmd")).isSuccess
    )
    test("issues") {
      test("dependency versions resolved by custom plugin") - assert(
        !eval("spectator-api.resolvedMvnDeps").isSuccess
      )
    }
  }
}
