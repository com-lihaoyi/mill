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

  trait Tests extends ScalaModuleTests with MavenModuleTests {
    override def sources = T.sources(
      millSourcePath / "src" / "test" / "scala",
      millSourcePath / "src" / "test" / "java"
    )
  }
  type SbtModuleTests = Tests
}
