package mill.javalib.android

import mill._
import mill.define._

/**
 * Trait for managing the Android SDK in a Mill build.
 *
 * This trait provides methods for downloading and setting up the Android SDK,
 * build tools, and other resources required for Android development.
 *
 * It simplifies the process of configuring the Android development environment,
 * making it easier to build and package Android applications.
 *
 * For detailed information, refer to Mill's Documentation [[https://com-lihaoyi.github.io/mill]],
 * and the Android Dcoumentation [[https://developer.android.com/studio]].
 */
trait AndroidSdkModule extends Module {

  /**
   * URL to download the Android SDK command-line tools.
   *
   * @return A string representing the URL for the SDK tools.
   */
  def SdkUrl: T[String] = T {
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  }

  /**
   * Version of Android build tools.
   *
   * @return A string representing the version of the build tools.
   */
  def BuildToolsVersion: T[String] = T { "35.0.0" }

  /**
   * Version of Android platform (e.g., Android API level).
   *
   * @return A string representing the platform version.
   */
  def PlatformVersion: T[String] = T { "android-35" }

  /**
   * Directory name for the Android command-line tools.
   *
   * @return A string representing the directory name for the tools.
   */
  def ToolsDirName: T[String] = T { "cmdline-tools" }

  /**
   * Name of the zip file containing the SDK tools.
   *
   * @return A string representing the zip file name.
   */
  def ZipFileName: T[String] = T { "commandlinetools.zip" }

  /**
   * Path where the Android SDK will be installed.
   *
   * @return A `PathRef` representing the SDK installation directory.
   */
  def sdkDirectory: T[PathRef] = T { PathRef(millSourcePath / "android-sdk") }

  /**
   * Path to the Android SDK command-line tools directory.
   *
   * @return A `PathRef` representing the command-line tools directory.
   */
  def toolsDirectory: T[PathRef] = T { PathRef(sdkDirectory().path / ToolsDirName().toString) }

  /**
   * Path to the Android build tools based on the selected version.
   *
   * @return A `PathRef` representing the build tools directory.
   *
   * @see [[BuildToolsVersion]]
   */
  def buildToolsPath: T[PathRef] =
    T { PathRef(sdkDirectory().path / "build-tools" / BuildToolsVersion().toString) }

  /**
   * Path to `android.jar`, required for compiling Android apps.
   *
   * @return A `PathRef` representing the path to `android.jar`.
   *
   * @see [[PlatformVersion]]
   */
  def androidJarPath: T[PathRef] =
    T { PathRef(sdkDirectory().path / "platforms" / PlatformVersion().toString / "android.jar") }

  /**
   * Path to the D8 Dex compiler, used to convert Java bytecode to Dalvik bytecode.
   *
   * @return A `PathRef` representing the path to the D8 compiler.
   *
   * @see [[buildToolsPath]]
   */
  def d8Path: T[PathRef] = T { PathRef(buildToolsPath().path / "d8") }

  /**
   * Path to the Android Asset Packaging Tool (AAPT) for handling resources and packaging APKs.
   *
   * @return A `PathRef` representing the path to the AAPT tool.
   *
   * @see [[buildToolsPath]]
   */
  def aaptPath: T[PathRef] = T { PathRef(buildToolsPath().path / "aapt") }

  /**
   * Path to Zipalign, used to optimize APKs.
   *
   * @return A `PathRef` representing the path to the zipalign tool.
   *
   * @see [[buildToolsPath]]
   */
  def zipalignPath: T[PathRef] = T { PathRef(buildToolsPath().path / "zipalign") }

  /**
   * Path to the APK signer tool, used to sign APKs.
   *
   * @return A `PathRef` representing the path to the APK signer tool.
   *
   * @see [[buildToolsPath]]
   */
  def apksignerPath: T[PathRef] = T { PathRef(buildToolsPath().path / "apksigner") }

  /**
   * Installs the Android SDK by downloading the tools, extracting them,
   * accepting licenses, and installing necessary components like platform and build tools.
   *
   * This method:
   * - Downloads the SDK command-line tools from the specified URL.
   *
   * - Extracts the downloaded zip file into the specified SDK directory.
   *
   * - Accepts the SDK licenses required for use.
   *
   * - Installs essential components such as platform-tools, build-tools and platforms.
   *
   * @see [[SdkUrl]]
   * @see [[toolsDirectory]]
   * @see [[sdkDirectory]]
   * @see [[BuildToolsVersion]]
   * @see [[PlatformVersion]]
   */
  def installAndroidSdk: T[Unit] = T {
    val zipFilePath: os.Path = sdkDirectory().path / ZipFileName().toString
    val sdkManagerPath: os.Path = toolsDirectory().path / "bin" / "sdkmanager"

    // Create SDK directory if it doesn't exist
    os.makeDir.all(sdkDirectory().path)

    // Download SDK command-line tools
    os.write(zipFilePath, requests.get(SdkUrl().toString).bytes)

    // Extract the zip into the SDK directory
    os.call(Seq("unzip", zipFilePath.toString, "-d", sdkDirectory().path.toString))

    // Accept SDK licenses
    os.call(Seq(
      "bash",
      "-c",
      s"yes | $sdkManagerPath --licenses --sdk_root=${sdkDirectory().path}"
    ))

    // Install platform-tools, build-tools, and platform
    os.call(Seq(
      sdkManagerPath.toString,
      s"--sdk_root=${sdkDirectory().path}",
      "platform-tools",
      s"build-tools;${BuildToolsVersion().toString}",
      s"platforms;${PlatformVersion().toString}"
    ))
  }
}
