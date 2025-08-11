package mill.androidlib

/**
 * Build type settings for
 * various packaging configurations.
 * See also [[https://developer.android.com/build/build-variants#build-types]]
 *
 * Useful for getting different packaging strategies for code shrinking and
 * supporting build variants
 */
case class AndroidBuildTypeSettings(
    isMinifyEnabled: Boolean = false,
    isShrinkEnabled: Boolean = false,
    enableDesugaring: Boolean = true
)

object AndroidBuildTypeSettings {
  implicit val resultRW: upickle.default.ReadWriter[AndroidBuildTypeSettings] =
    upickle.default.macroRW
}
