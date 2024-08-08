package mill.scalalib

import mill.{Task, T}

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.sources(
    millSourcePath / "src" / "main" / "scala",
    millSourcePath / "src" / "main" / "java"
  )

  type SbtTests = SbtModuleTests
  @deprecated("Use SbtTests instead", since = "Mill 0.11.10")
  trait SbtModuleTests extends ScalaTests with MavenTests {
    override def sources = Task.sources(
      millSourcePath / "src" / "test" / "scala",
      millSourcePath / "src" / "test" / "java"
    )
  }
}
