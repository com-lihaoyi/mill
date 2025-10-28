package mill.integration
import utest.*
object MillInitMavenNettyTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/netty/netty.git",
      "netty-4.2.6.Final",
      failingTasks = Seq("buffer.test.discoveredTestClasses")
    )
  }
}
