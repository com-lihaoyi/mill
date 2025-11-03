package mill.javalib

import mill.Task

import java.nio.file.Path

/**
 * A [[JavaModule]] with a Maven compatible directory layout.
 * `src/main/java`, `src/test/resources`, etc.
 *
 * @see [[SbtModule]] if you need a scala module with Maven layout.
 */
trait MavenModule extends JavaModule { outer =>

  // Bincompat stub
  private[mill] def sourcesFolders0 = Seq(os.sub / "src/main/java")
  override def sourcesFolders = sourcesFolders0
  // Bincompat stub
  override def sources = Task.Sources(sourcesFolders*)
  override def resources = Task.Sources("src/main/resources")

  trait MavenTests extends JavaTests with MavenModule {
    override def moduleDir = outer.moduleDir

    /**
     * The name of this module's folder within `src/`: e.g. `src/test/`, `src/integration/`,
     * etc. Defaults to the name of the module object, but can be overridden by users
     */
    def testModuleName = moduleCtx.segments.last.value

    private[mill] override def intellijModulePathJava: Path =
      (outer.moduleDir / "src" / testModuleName).toNIO

    // Bincompat stub
    private[mill] override def sourcesFolders0 = Seq(os.sub / "src" / testModuleName / "java")
    override def sourcesFolders = sourcesFolders0
    // Bincompat stub
    override def sources = Task.Sources(sourcesFolders*)
    override def resources = Task.Sources(moduleDir / "src" / testModuleName / "resources")
  }
}
