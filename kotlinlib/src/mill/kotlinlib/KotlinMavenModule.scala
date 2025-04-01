package mill.kotlinlib

import mill.Task
import mill.javalib.MavenModule

/**
 * A [[KotlinModule]] with a Maven compatible directory layout.
 */
trait KotlinMavenModule extends KotlinModule with MavenModule {
  private def sources0 = Task.Sources("src/main/kotlin")
  override def sources = super.sources() ++ sources0()

  trait KotlinMavenTests extends KotlinTests with MavenTests {
    override def intellijModulePath: os.Path = millSourcePath / "src/test"

    private def sources0 = Task.Sources("src/test/kotlin")
    override def sources = super.sources() ++ sources0()
  }
}
