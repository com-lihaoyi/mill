package mill.scalalib

import mill.Task

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.Sources("src/main/scala", "src/main/java")

  trait SbtTests extends ScalaTests with MavenTests {
    override def sources = Task.Sources("src/test/scala", "src/test/java")
  }
}
