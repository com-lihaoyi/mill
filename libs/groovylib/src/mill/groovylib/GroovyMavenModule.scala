package mill.groovylib

import mill.Task
import mill.javalib.MavenModule

/**
 * A [[GroovyModule]] with a Maven compatible directory layout:
 * `src/main/groovy`, `src/main/resources`, etc.
 */
@mill.api.experimental
trait GroovyMavenModule extends GroovyModule with MavenModule {
  private def sources0 = Task.Sources("src/main/groovy")
  override def sources = super.sources() ++ sources0()

  trait GroovyMavenTests extends GroovyTests with MavenTests {
    override def sources = Task.Sources(
      moduleDir / "src" / testModuleName / "java",
      moduleDir / "src" / testModuleName / "groovy"
    )
  }
}
