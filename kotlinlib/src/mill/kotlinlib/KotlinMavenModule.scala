package mill.kotlinlib

import mill.T
import mill.javalib.MavenModule

/**
 * A [[KotlinModule]] with a Maven compatible directory layout.
 */
trait KotlinMavenModule extends KotlinModule with MavenModule {
  override def sources = T.sources(
    millSourcePath / "src/main/java",
    millSourcePath / "src/main/kotlin"
  )
  override def resources = T.sources {
    millSourcePath / "src/main/resources"
  }

  trait KotlinMavenTests extends KotlinTests with MavenTests {
    override def intellijModulePath: os.Path = millSourcePath / "src/test"

    override def sources = T.sources(
      millSourcePath / "src/test/java",
      millSourcePath / "src/test/kotlin"
    )
    override def resources = T.sources {
      millSourcePath / "src/test/resources"
    }
  }
}
