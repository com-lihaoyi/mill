package mill.integration
import utest.*
object InitMavenNettyTests extends InitTestSuite(
      "https://github.com/netty/netty",
      "netty-4.2.10.Final",
      Seq("--mill-jvm-id", "17")
    ) {
  def tests = Tests {
    test("compile") - assert(
      eval("buffer.compile").isSuccess
    )
    test("test") - assert(
      eval("buffer.test").isSuccess
    )
    test("checkstyle") - assert(
      eval(("resolve", "buffer.checkstyle")).isSuccess
    )
    test("revapi") - assert(
      eval(("resolve", "buffer.revapi")).isSuccess
    )
    test("issues") {
      test("missing generated sources") - assert(
        !eval("codec-dns.compile").isSuccess
      )
      test("javadocGenerated fails") - assert(
        !eval("buffer.javadocGenerated").isSuccess
      )
      test("checkstyle config file not found") - assert(
        !eval("buffer.checkstyle").isSuccess
      )
      test("revapi requires javacOptions for JPMS") - assert(
        !eval("buffer.revapi").isSuccess
      )
    }
  }
}
