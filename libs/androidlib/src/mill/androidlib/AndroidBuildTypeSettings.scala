package mill.androidlib

import mill.api.PathRef

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
    enableDesugaring: Boolean = true,
    proguardFiles: ProguardFiles = ProguardFiles()
) {
  def withProguardLocalFiles(localFiles: Seq[PathRef]): AndroidBuildTypeSettings =
    copy(proguardFiles = proguardFiles.copy(localFiles = localFiles))

  def withDefaultProguardFile(fileName: String): AndroidBuildTypeSettings =
    copy(proguardFiles = proguardFiles.copy(defaultProguardFile = Some(fileName)))
}

case class ProguardFiles(
    defaultProguardFile: Option[String] = None,
    localFiles: Seq[PathRef] = List.empty
)

object AndroidBuildTypeSettings {
  implicit val resultRW: upickle.default.ReadWriter[AndroidBuildTypeSettings] =
    upickle.default.macroRW
}

object ProguardFiles {
  implicit val resultRW: upickle.default.ReadWriter[ProguardFiles] = upickle.default.macroRW
}
