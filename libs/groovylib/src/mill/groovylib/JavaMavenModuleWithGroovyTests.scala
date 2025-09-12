package mill.groovylib

import mill.*
import mill.javalib.{JavaModule, MavenModule}

/**
 * Convenience trait for projects using Java for production and Groovy for tests in a Maven setup
 */
trait JavaMavenModuleWithGroovyTests extends JavaModule with MavenModule {

  trait GroovyMavenTests extends JavaTests with MavenTests with GroovyModule {
    private def groovyTestSources = Task.Sources(moduleDir / "src" / testModuleName / "groovy")
    override def sources: T[Seq[PathRef]] = super[MavenTests].sources() ++ groovyTestSources()
    override def resources: T[Seq[PathRef]] = super[MavenTests].resources()
  }
}
