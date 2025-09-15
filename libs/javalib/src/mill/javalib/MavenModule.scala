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
    override def moduleDir = outerRef().moduleDir

    /**
     * The name of this module's folder within `src/`: e.g. `src/test/`, `src/integration/`,
     * etc. Defaults to the name of the module object, but can be overridden by users
     */
    def testModuleName = moduleCtx.segments.last.value

    private[mill] override def intellijModulePathJava: Path =
      (outerRef().moduleDir / "src" / testModuleName).toNIO

    override def sources = Task.Sources(moduleDir / "src" / testModuleName / "java")
    override def resources = Task.Sources(moduleDir / "src" / testModuleName / "resources")
  }
}
