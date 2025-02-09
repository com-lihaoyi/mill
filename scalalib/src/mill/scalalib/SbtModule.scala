package mill.scalalib

import mill.Task

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.Sources(
    modulePath / "src/main/scala",
    modulePath / "src/main/java"
  )

  trait SbtTests extends ScalaTests with MavenTests {
    override def sources = Task.Sources(
      modulePath / "src/test/scala",
      modulePath / "src/test/java"
    )
  }
}
