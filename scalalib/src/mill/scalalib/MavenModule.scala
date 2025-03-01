package mill.scalalib

import mill.Task

/**
 * A [[JavaModule]] with a Maven compatible directory layout.
 *
 * @see [[SbtModule]] if you need a scala module with Maven layout.
 */
trait MavenModule extends JavaModule { outer =>

  override def sources = Task.Sources("src/main/java")
  override def resources = Task.Sources("src/main/resources")

  trait MavenTests extends JavaTests {
    override def moduleDir = outer.moduleDir
    override def intellijModulePath: os.Path = outer.moduleDir / "src/test"

    override def sources = Task.Sources("src/test/java")
    override def resources = Task.Sources("src/test/resources")
  }
}
