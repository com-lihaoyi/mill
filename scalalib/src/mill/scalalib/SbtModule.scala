package mill.scalalib

import mill.Task

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.Sources(
    moduleBase / "src/main/scala",
    moduleBase / "src/main/java"
  )

  trait SbtTests extends ScalaTests with MavenTests {
    override def sources = Task.Sources(
      moduleBase / "src/test/scala",
      moduleBase / "src/test/java"
    )
  }
}
