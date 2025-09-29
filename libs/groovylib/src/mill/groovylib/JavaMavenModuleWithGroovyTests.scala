package mill.groovylib

import mill.*
import mill.javalib.{JavaModule, MavenModule}

/**
 * Convenience trait for projects using Java for production and Groovy for tests in a Maven setup.
 * 
 * Since [[GroovyModule.GroovyTests]] is only available as a child-trait, it is necessary to have
 * the main module as a [[GroovyModule]], which would implicitly add Groovy dependencies to the 
 * module.
 * This trait explicitly uses Java with a Maven layout for the main module and enables `src/test/groovy`
 * as a source folder for Groovy tests.
 * 
 * {{{
 *     object `package` extends JavaMavenModuleWithGroovyTests {
 *
 *       object `test` extends GroovyMavenTests with TestModule.Spock {
 *         override def groovyVersion: T[String] = "4.0.28"
 *         override def spockVersion: T[String] = "2.3-groovy-4"
 *       }
 *     }
 * }}}
 * 
 * Note: for non-Maven layouts this is not necessary, since the test module can just be a [[GroovyModule]].
 */
@mill.api.experimental
trait JavaMavenModuleWithGroovyTests extends JavaModule with MavenModule {

  trait GroovyMavenTests extends JavaTests with MavenTests with GroovyModule {
    private def groovyTestSources = Task.Sources(moduleDir / "src" / testModuleName / "groovy")
    override def sources: T[Seq[PathRef]] = super[MavenTests].sources() ++ groovyTestSources()
    override def resources: T[Seq[PathRef]] = super[MavenTests].resources()
  }
}
