package mill.kotlinlib

import mill.scalalib.TestModule

/**
 * A [[TestModule]] with support for the Kotlin compiler.
 *
 * @see [[KotlinModule]] for details.
 */
trait KotlinTestModule extends TestModule with KotlinModule {
  override def defaultCommandName(): String = super.defaultCommandName()
}
