package mill.androidlib

import mill.define.JsonFormatters.pathReadWrite

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
    enableDesugaring: Boolean = false,
    proguardFiles: ProguardFiles = ProguardFiles()
) {
  def withProguardLocalFiles(localFiles: Seq[os.Path]): AndroidBuildTypeSettings =
    copy(proguardFiles = proguardFiles.copy(localFiles = localFiles))
}

case class ProguardFiles(
    defaultProguardFile: Option[String] = None,
    localFiles: Seq[os.Path] = List.empty
)

object AndroidBuildTypeSettings {
  implicit val resultRW: upickle.default.ReadWriter[AndroidBuildTypeSettings] =
    upickle.default.macroRW
}

object ProguardFiles {
  implicit val resultRW: upickle.default.ReadWriter[ProguardFiles] = upickle.default.macroRW
}
