package mill.scalalib

import mill.Task

import scala.annotation.nowarn

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.Sources(
    millSourcePath / "src/main/scala",
    millSourcePath / "src/main/java"
  )

  @nowarn
  type SbtTests = SbtModuleTests
  @deprecated("Use SbtTests instead", since = "Mill 0.11.10")
  trait SbtModuleTests extends ScalaTests with MavenTests {
    override def sources = Task.Sources(
      millSourcePath / "src/test/scala",
      millSourcePath / "src/test/java"
    )
  }
}
