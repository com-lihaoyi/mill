package mill.scalalib.buildfile

import mill.T
import mill.api.experimental
import mill.define.{Discover, ExternalModule}

/**
 * Same as [[MillBuildModule]], but supports some using directives.
 */
@experimental
object MillNextBuildModule extends ExternalModule with MillBuildModule {
  lazy val millDiscover: Discover[this.type] = Discover[this.type]

  override def supportUsingDirectives: T[Boolean] = T(true)
}
