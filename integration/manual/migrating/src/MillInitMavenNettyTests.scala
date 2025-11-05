package mill.integration
import utest.*
object MillInitMavenNettyTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://github.com/netty/netty.git",
      "netty-4.2.7.Final",
      passingTasks = Seq("codec-compression.compile"),
      failingTasks = Seq(
        // missing generated sources
        "codec-dns.compile",
        // Junit5 engine and launcher version mismatch
        "buffer.test.discoveredTestClasses"
      )
    )
  }
}
