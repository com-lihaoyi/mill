package mill.kotlinlib

import mill.Task
import mill.api.ModuleRef
import mill.javalib.MavenModule

/**
 * A [[KotlinModule]] with a Maven compatible directory layout:
 * `src/main/kotlin`, `src/test/resources`, etc.
 */
trait KotlinMavenModule extends KotlinModule with MavenModule {
  private def sources0 = Task.Sources("src/main/kotlin")
  override def sources = super.sources() ++ sources0()

  trait KotlinMavenTests extends KotlinTests with MavenTests {
    def outerRef = ModuleRef(KotlinMavenModule.this)
    override def sources = Task.Sources(
      moduleDir / "src" / testModuleName / "java",
      moduleDir / "src" / testModuleName / "kotlin"
    )
  }
}
