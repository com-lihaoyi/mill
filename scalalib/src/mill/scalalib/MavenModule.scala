package mill.scalalib

import mill.{Task, T}

/**
 * A [[JavaModule]] with a Maven compatible directory layout.
 *
 * @see [[SbtModule]] if you need a scala module with Maven layout.
 */
trait MavenModule extends JavaModule { outer =>

  override def sources = Task.sources(
    millSourcePath / "src" / "main" / "java"
  )
  override def resources = Task.sources {
    millSourcePath / "src" / "main" / "resources"
  }

  type MavenTests = MavenModuleTests
  @deprecated("Use JavaTests instead", since = "Mill 0.11.10")
  trait MavenModuleTests extends JavaModuleTests {
    override def millSourcePath = outer.millSourcePath
    override def intellijModulePath: os.Path = outer.millSourcePath / "src" / "test"

    override def sources = Task.sources(
      millSourcePath / "src" / "test" / "java"
    )
    override def resources = Task.sources {
      millSourcePath / "src" / "test" / "resources"
    }
  }
}
