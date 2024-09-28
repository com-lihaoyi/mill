package mill.javalib.android

import mill.define._

/**
 * A trait representing an Android module within Mill build tool.
 * This trait handles Android SDK installation, managing SDK tools,
 * build tools, platform-specific libraries, and related utilities.
 */
trait AndroidModule extends Module {

  /**
   * URL from which the Android SDK command-line tools are downloaded.
   */
  val sdkUrl: String =
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

  /**
   * Directory where the Android SDK will be installed.
   * Default is the 'android-sdk' directory within the Mill source path.
   */
  val sdkDir: os.Path = millSourcePath / "android-sdk"

  /**
   * Path to the Android SDK command-line tools.
   */
  val toolsDir: os.Path = sdkDir / "cmdline-tools"

  /**
   * Version of the Android build tools to be used.
   */
  val buildToolsVersion: String = "35.0.0"

  /**
   * Android platform version that will be targeted.
   */
  val platformVersion: String = "android-35"

  /**
   * Path to the Android build tools, based on the specified build tools version.
   */
  val buildToolsPath: os.Path = sdkDir / "build-tools" / buildToolsVersion

  /**
   * Path to the `android.jar` for the specified Android platform.
   */
  val androidJarPath: os.Path = sdkDir / "platforms" / platformVersion / "android.jar"

  /**
   * Path to the D8 Dex compiler, used for converting Java bytecode into Dalvik bytecode.
   */
  val d8: os.Path = buildToolsPath / "d8"

  /**
   * Path to the Android Asset Packaging Tool (AAPT), used for packaging Android apps.
   */
  val aapt: os.Path = buildToolsPath / "aapt"

  /**
   * Path to the Zipalign tool, used to optimize APKs for performance.
   */
  val zipalign: os.Path = buildToolsPath / "zipalign"

  /**
   * Path to the APK Signer tool, used for signing Android APK files.
   */
  val apksigner: os.Path = buildToolsPath / "apksigner"

  /**
   * Downloads and installs the Android SDK, including build tools and platform tools.
   *
   * This method performs the following steps:
   * 1. Downloads the Android SDK command-line tools from the specified URL if not already downloaded.
   * 2. Unzips the tools to the SDK directory and sets up necessary licenses.
   * 3. Installs the required Android platform and build-tools using `sdkmanager`.
   *
   * If any of these steps have already been completed (e.g., tools are already installed),
   * the method will skip those steps and exit early.
   */
  def installAndroidSdk(): Unit = {
    // Step 1: Download the Android SDK command-line tools
    val zipFile: os.Path = os.pwd / "commandlinetools-linux.zip"
    if (!os.exists(zipFile)) {
      os.write(zipFile, requests.get(sdkUrl).bytes)
    } else {
      return // Exit the function if else block is executed
    }

    // Step 2: Unzip the SDK tools
    if (!os.exists(toolsDir)) {
      os.makeDir.all(toolsDir)
      os.proc("unzip", zipFile, "-d", toolsDir).call()
      val licensesDir: os.Path = sdkDir / "licenses"
      os.makeDir.all(licensesDir)
      os.write(licensesDir / "android-sdk-license", "24333f8a63b6825ea9c5514f83c2829b004d1fee")
    } else {
      return // Exit the function if else block is executed
    }

    // Step 3: Install the required platforms and build-tools
    val sdkManagerPath: os.Path = toolsDir / "cmdline-tools" / "bin" / "sdkmanager"
    if (os.exists(sdkManagerPath)) {
      os.proc(
        sdkManagerPath,
        "--sdk_root=" + sdkDir.toString,
        "platform-tools",
        s"build-tools;$buildToolsVersion",
        s"platforms;$platformVersion"
      ).call()
    } else {
      return // Exit the function if SDK manager is not found
    }
  }
}
