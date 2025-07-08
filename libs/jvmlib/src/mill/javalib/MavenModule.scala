package mill.javalib

import java.nio.file.Path

import mill.Task

/**
 * A [[JavaModule]] with a Maven compatible directory layout.
 * `src/main/java`, `src/test/resources`, etc.
 *
 * @see [[SbtModule]] if you need a scala module with Maven layout.
 */
trait MavenModule extends JavaModule { outer =>

  override def sources = Task.Sources("src/main/java")
  override def resources = Task.Sources("src/main/resources")

  trait MavenTests extends JavaTests {
    override def moduleDir = outer.moduleDir
    private[mill] override def intellijModulePathJava: Path = (outer.moduleDir / "src/test").toNIO

    override def sources = Task.Sources("src/test/java")
    override def resources = Task.Sources("src/test/resources")
  }
}
