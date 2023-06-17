package mill.scalalib

import mill.T

/**
 * A [[JavaModule]] with a Maven compatible directory layout.
 *
 * @see [[SbtModule]] if you need a scala module with Maven layout.
 */
trait MavenModule extends JavaModule { outer =>

  override def sources = T.sources(
    millSourcePath / "src" / "main" / "java"
  )
  override def resources = T.sources {
    millSourcePath / "src" / "main" / "resources"
  }

  trait MavenModuleTests extends JavaModuleTests {
    override def millSourcePath = outer.millSourcePath
    override def intellijModulePath: os.Path = outer.millSourcePath / "src" / "test"

    override def sources = T.sources(
      millSourcePath / "src" / "test" / "java"
    )
    override def resources = T.sources {
      millSourcePath / "src" / "test" / "resources"
    }
  }
}
