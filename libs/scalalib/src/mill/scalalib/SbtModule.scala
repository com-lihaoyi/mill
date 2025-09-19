package mill.scalalib

import mill.Task
import mill.api.ModuleRef

/**
 * A [[ScalaModule]] with sbt compatible directory layout.
 */
trait SbtModule extends ScalaModule with MavenModule {

  override def sources = Task.Sources("src/main/scala", "src/main/java")

  trait SbtTests extends ScalaTests with MavenTests {
    def outerRef = ModuleRef(SbtModule.this)
    override def sources = Task.Sources(
      moduleDir / "src" / testModuleName / "java",
      moduleDir / "src" / testModuleName / "scala"
    )
  }
}
