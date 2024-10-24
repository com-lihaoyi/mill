package mill.javalib.android

import mill._
import scala.util.Try

/**
 * Trait for managing the Android SDK in a Mill build system.
 *
 * This trait offers utility methods for automating the download, installation,
 * and configuration of the Android SDK, build tools, and other essential
 * components necessary for Android development. It facilitates setting up
 * an Android development environment, streamlining the process of building,
 * compiling, and packaging Android applications in a Mill project.
 *
 * For more information, refer to the official Android
 * [[https://developer.android.com/studio documentation]].
 */
@mill.api.experimental
trait AndroidSdkModule extends Module {

  /**
   * Provides the URL to download the Android SDK command-line tools.
   */
  def sdkUrl: T[String] = Task {
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  }

  /**
   * Specifies the version of the Android build tools to be used.
   */
  def buildToolsVersion: T[String]

  /**
   * Specifies the Android platform version (e.g., Android API level).
   */
  def platformsVersion: T[String] = Task { "android-" + buildToolsVersion().split('.').head }

  /**
   * Provides the path to the `android.jar` file, necessary for compiling Android apps.
   */
  def androidJarPath: T[PathRef] = Task {
    PathRef(installAndroidSdk().path / "platforms" / platformsVersion().toString / "android.jar")
  }

  /**
   * Provides path to the Android build tools for the selected version.
   */
  def buildToolsPath: T[PathRef] =
    Task { PathRef(installAndroidSdk().path / "build-tools" / buildToolsVersion().toString) }

  /**
   * Provides path to D8 Dex compiler, used for converting Java bytecode into Dalvik bytecode.
   */
  def d8Path: T[PathRef] = Task {
    PathRef(buildToolsPath().path / "d8")
  }

  /**
   * Provides the path to AAPT, used for resource handling and APK packaging.
   */
  def aaptPath: T[PathRef] = Task {
    PathRef(buildToolsPath().path / "aapt")
  }

  /**
   * Provides the path to AAPT2, used for resource handling and APK packaging.
   */
  def aapt2Path: T[PathRef] = Task {
    PathRef(buildToolsPath().path / "aapt2")
  }

  /**
   * Provides the path to the Zipalign tool, which optimizes APK files by aligning their data.
   */
  def zipalignPath: T[PathRef] = Task {
    PathRef(buildToolsPath().path / "zipalign")
  }

  /**
   * Provides the path to the APK signer tool, used to digitally sign APKs.
   */
  def apksignerPath: T[PathRef] = Task {
    PathRef(buildToolsPath().path / "apksigner")
  }

  /**
   * Installs the Android SDK by performing the following steps:
   *
   * 1. Downloads the SDK command-line tools if not already cached.
   *
   * 2. Extracts the downloaded tools into the SDK directory.
   *
   * 3. Accepts SDK licenses automatically.
   *
   * 4. Installs the following components if not already installed:
   *    - platform-tools
   *    - build-tools (version specified by `buildToolsVersion`)
   *    - platforms (version specified by `platformsVersion`)
   *
   * 5. Removes the downloaded zip file after extraction.
   *
   * @return A task returning a `PathRef` pointing to the installed SDK directory.
   *
   * @see [[https://developer.android.com/tools/sdkmanager sdkmanager Documentation]]
   */
  def installAndroidSdk: T[PathRef] = Task {
    val sdkCache: os.Path = os.home / ".android-sdk-cache"
    val zipFilePath: os.Path = sdkCache / "commandlinetools.zip"
    val sdkManagerPath: os.Path = sdkCache / "cmdline-tools/bin/sdkmanager"

    // Helper method to check if a tool is installed
    def isToolInstalled(toolPath: os.Path): Boolean = os.exists(toolPath)

    // Paths for the tools
    val platformToolsPath: os.Path = sdkCache / "platform-tools"
    val buildToolsPath: os.Path = sdkCache / "build-tools" / buildToolsVersion()
    val platformsPath: os.Path = sdkCache / "platforms" / platformsVersion()

    // Check if the SDK manager is already cached
    if (!os.exists(sdkManagerPath)) {
      // Ensure cache directory exists
      os.makeDir.all(sdkCache)
      // Download SDK command-line tools
      os.write(zipFilePath, requests.get(sdkUrl().toString).bytes)

      // Extract the downloaded SDK tools into the destination directory
      os.call(Seq("unzip", zipFilePath.toString, "-d", sdkCache.toString))

      // Automatically accept the SDK licenses
      os.call(Seq(
        "bash",
        "-c",
        s"yes | $sdkManagerPath --licenses --sdk_root=${sdkCache}"
      ))

      // Clean up the downloaded zip file
      Try(os.remove(zipFilePath))
    }

    // Install platform-tools if not already installed
    if (!isToolInstalled(platformToolsPath)) {
      os.call(Seq(
        sdkManagerPath.toString,
        s"--sdk_root=${sdkCache}",
        "platform-tools"
      ))
    }

    // Install build-tools if not already installed
    if (!isToolInstalled(buildToolsPath)) {
      os.call(Seq(
        sdkManagerPath.toString,
        s"--sdk_root=${sdkCache}",
        s"build-tools;${buildToolsVersion().toString}"
      ))
    }

    // Install platforms if not already installed
    if (!isToolInstalled(platformsPath)) {
      os.call(Seq(
        sdkManagerPath.toString,
        s"--sdk_root=${sdkCache}",
        s"platforms;${platformsVersion().toString}"
      ))
    }

    PathRef(sdkCache)
  }

}
