package mill.kotlinlib

import java.nio.file.Path

import mill.Task
import mill.javalib.MavenModule

/**
 * A [[KotlinModule]] with a Maven compatible directory layout:
 * `src/main/kotlin`, `src/test/resources`, etc.
 */
trait KotlinMavenModule extends KotlinModule with MavenModule {
  private def sources0 = Task.Sources("src/main/kotlin")
  override def sources = super.sources() ++ sources0()

  trait KotlinMavenTests extends KotlinTests with MavenTests {
    private def sources0 = Task.Sources("src/test/kotlin")
    override def sources = super.sources() ++ sources0()
  }
}
