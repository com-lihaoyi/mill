package mill.scalalib

import mill.Task

import scala.annotation.nowarn

/**
 * A [[JavaModule]] with a Maven compatible directory layout.
 *
 * @see [[SbtModule]] if you need a scala module with Maven layout.
 */
trait MavenModule extends JavaModule { outer =>

  override def sources = Task.Sources(
    millSourcePath / "src/main/java"
  )
  override def resources = Task.Sources {
    millSourcePath / "src/main/resources"
  }

  @nowarn
  type MavenTests = MavenModuleTests
  @deprecated("Use MavenTests instead", since = "Mill 0.11.10")
  trait MavenModuleTests extends JavaTests {
    override def millSourcePath = outer.millSourcePath
    override def intellijModulePath: os.Path = outer.millSourcePath / "src/test"

    override def sources = Task.Sources(
      millSourcePath / "src/test/java"
    )
    override def resources = Task.Sources {
      millSourcePath / "src/test/resources"
    }
  }
}
