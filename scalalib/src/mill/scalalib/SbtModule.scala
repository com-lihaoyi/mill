package mill.scalalib

import mill.Task

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.Sources(
    moduleDir / "src/main/scala",
    moduleDir / "src/main/java"
  )

  trait SbtTests extends ScalaTests with MavenTests {
    override def sources = Task.Sources(
      moduleDir / "src/test/scala",
      moduleDir / "src/test/java"
    )
  }
}
