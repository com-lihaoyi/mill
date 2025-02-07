package mill.scalalib

import mill.Task

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

  trait MavenTests extends JavaTests {
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
