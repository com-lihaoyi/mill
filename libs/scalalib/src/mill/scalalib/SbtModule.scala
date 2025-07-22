package mill.scalalib

import mill.Task

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.Sources("src/main/scala", "src/main/java")

  trait SbtTests extends ScalaTests with MavenTests {
    private def sources0 = Task.Sources(moduleDir / "src" / testModuleName / "scala")
    override def sources = super.sources() ++ sources0()
  }
}
