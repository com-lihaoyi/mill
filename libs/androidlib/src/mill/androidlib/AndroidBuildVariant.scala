package mill.androidlib

import mill.*
import mill.api.Cross

/**
 * Represents an Android build variant, such as "debug" or "release".
 */
@mill.api.experimental
sealed trait AndroidBuildVariant {
  def name: String

}
object AndroidBuildVariant {
  case object Debug extends AndroidBuildVariant {
    def name = "debug"
  }

  case object Release extends AndroidBuildVariant {
    def name = "release"
  }

  def variants: Seq[AndroidBuildVariant] =
    Seq(Debug, Release)

  implicit object VariantToSegments extends Cross.ToSegments[AndroidBuildVariant](v =>
        List(v.name)
      )
}
