package mill.scalalib

import mill.Task

import scala.annotation.nowarn

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.Sources(
    "src/main/scala",
    "src/main/java"
  )

  @nowarn
  type SbtTests = SbtModuleTests
  @deprecated("Use SbtTests instead", since = "Mill 0.11.10")
  trait SbtModuleTests extends ScalaTests with MavenTests {
    override def sources = Task.Sources(
      "src/test/scala",
      "src/test/java"
    )
  }
}
