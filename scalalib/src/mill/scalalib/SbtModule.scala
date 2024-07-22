package mill.scalalib

import mill.T

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = T.sources(
    millSourcePath / "src" / "main" / "scala",
    millSourcePath / "src" / "main" / "java"
  )

  type SbtTests = SbtModuleTests
  @deprecated("0.11.10, use SbtTests instead")
  trait SbtModuleTests extends ScalaTests with MavenTests {
    override def sources = T.sources(
      millSourcePath / "src" / "test" / "scala",
      millSourcePath / "src" / "test" / "java"
    )
  }
}
