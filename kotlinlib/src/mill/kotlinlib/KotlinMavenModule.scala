package mill.kotlinlib

import mill.T

/**
 * A [[KotlinModule]] with a Maven compatible directory layout.
 */
trait KotlinMavenModule extends KotlinModule { outer =>
  override def sources = T.sources(
    millSourcePath / "src/main/java",
    millSourcePath / "src/main/kotlin"
  )
  override def resources = T.sources {
    millSourcePath / "src/main/resources"
  }

  trait KotlinMavenModuleTests extends KotlinModuleTests {
    override def millSourcePath = outer.millSourcePath
    override def intellijModulePath: os.Path = outer.millSourcePath / "src/test"

    override def sources = T.sources(
      millSourcePath / "src/test/java",
      millSourcePath / "src/test/kotlin"
    )
    override def resources = T.sources {
      millSourcePath / "src/test/resources"
    }
  }
}
