package mill.scalalib

import mill.Task


/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.Sources(
    millSourcePath / "src/main/scala",
    millSourcePath / "src/main/java"
  )

  trait SbtTests extends ScalaTests with MavenTests {
    override def sources = Task.Sources(
      millSourcePath / "src/test/scala",
      millSourcePath / "src/test/java"
    )
  }
}
