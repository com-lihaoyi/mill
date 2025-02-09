package mill.scalalib

import mill.Task

/**
 * A [[JavaModule]] with a Maven compatible directory layout.
 *
 * @see [[SbtModule]] if you need a scala module with Maven layout.
 */
trait MavenModule extends JavaModule { outer =>

  override def sources = Task.Sources(
    moduleBase / "src/main/java"
  )
  override def resources = Task.Sources {
    moduleBase / "src/main/resources"
  }

  trait MavenTests extends JavaTests {
    override def moduleBase = outer.moduleBase
    override def intellijModulePath: os.Path = outer.moduleBase / "src/test"

    override def sources = Task.Sources(
      moduleBase / "src/test/java"
    )
    override def resources = Task.Sources {
      moduleBase / "src/test/resources"
    }
  }
}
