package mill.javalib.android

import mill._

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
   * Installs the Android SDK by performing the following actions:
   *
   * - Downloads the SDK command-line tools from the specified URL.
   *
   * - Extracts the downloaded zip file into the SDK directory.
   *
   * - Accepts the required SDK licenses.
   *
   * - Installs essential SDK components such as platform-tools, build-tools, and Android platforms.
   *
   * For more details on the sdkmanager tool, refer to:
   * [[https://developer.android.com/tools/sdkmanager sdkmanager Documentation]]
   *
   * @return A task containing a `PathRef` pointing to the installed SDK directory.
   */
  def installAndroidSdk: T[PathRef] = Task {
    val zipFilePath: os.Path = T.dest / "commandlinetools.zip"
    val sdkManagerPath: os.Path = T.dest / "cmdline-tools/bin/sdkmanager"

    // Download SDK command-line tools
    os.write(zipFilePath, requests.get(sdkUrl().toString).bytes)

    // Extract the downloaded SDK tools into the destination directory
    os.call(Seq("unzip", zipFilePath.toString, "-d", T.dest.toString))

    // Automatically accept the SDK licenses
    os.call(Seq(
      "bash",
      "-c",
      s"yes | $sdkManagerPath --licenses --sdk_root=${T.dest}"
    ))

    // Install platform-tools, build-tools, and the Android platform
    os.call(Seq(
      sdkManagerPath.toString,
      s"--sdk_root=${T.dest}",
      "platform-tools",
      s"build-tools;${buildToolsVersion().toString}",
      s"platforms;${platformsVersion().toString}"
    ))
    PathRef(T.dest)
  }
}
