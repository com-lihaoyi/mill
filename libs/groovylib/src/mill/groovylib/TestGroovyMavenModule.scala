package mill.groovylib

import mill.*

/**
 * A [[GroovyModule]] for tests with a Maven compatible directory layout:
 * `src/test/groovy`, `src/test/resources`, etc.
 * This is useful when only the tests are written in Groovy while the production code remains in Java or other.
 *
 * Requires an outer module (like JavaModule) otherwise [[moduleDir]] must be overridden manually.
 */
trait TestGroovyMavenModule extends GroovyMavenModule {

  /**
   * The name of this module's folder within `src/`: e.g. `src/test/`, `src/integration/`,
   * etc. Defaults to the name of the module object, but can be overridden by users
   */
  def testModuleName: String = moduleCtx.segments.last.value

  private def testSources = Task.Sources(moduleDir / os.up / "src" / testModuleName / "groovy")
  override def sources: T[Seq[PathRef]] = testSources()

  private def testResources = Task.Sources(moduleDir / os.up / "src" / testModuleName / "resources")
  override def resources: T[Seq[PathRef]] = testResources()
}
