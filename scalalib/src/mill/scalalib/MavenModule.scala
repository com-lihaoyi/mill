package mill.scalalib

import mill.Task

/**
 * A [[JavaModule]] with a Maven compatible directory layout.
 *
 * @see [[SbtModule]] if you need a scala module with Maven layout.
 */
trait MavenModule extends JavaModule { outer =>

  override def sources = Task.Sources(
    modulePath / "src/main/java"
  )
  override def resources = Task.Sources {
    modulePath / "src/main/resources"
  }

  trait MavenTests extends JavaTests {
    override def modulePath = outer.modulePath
    override def intellijModulePath: os.Path = outer.modulePath / "src/test"

    override def sources = Task.Sources(
      modulePath / "src/test/java"
    )
    override def resources = Task.Sources {
      modulePath / "src/test/resources"
    }
  }
}
