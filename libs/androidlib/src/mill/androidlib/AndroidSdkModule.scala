package mill.androidlib

import coursier.MavenRepository
import coursier.cache.CachePolicy.LocalOnly
import coursier.cache.FileCache
import coursier.util.Artifact
import mill.*
import mill.androidlib.Versions
import mill.api.{ModuleRef, Result, TaskCtx}

import scala.util.chaining.given

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

  // this has a format `repository2-%d`, where the last number is schema version. For the needs of this module it
  // is okay to stick with the particular version.
  private val remotePackagesUrl = "https://dl.google.com/android/repository/repository2-3.xml"

  /**
   * Specifies the version of the internal Command Line Tools to be used.
   */
  def millCmdlineToolsVersion: T[String] = Task.Input {
    Versions.millCmdlineToolsVersion
  }

  def androidMillHomeDir: T[PathRef] = Task {
    PathRef(androidSdkManagerModule().androidMillHomeDir())
  }

  /**
   * Specifies the version of the Android Bundle tool to be used.
   */
  def bundleToolVersion: T[String] = Task.Input {
    Versions.bundleToolVersion
  }

  /**
   * Specifies the version of the Manifest Merger.
   */
  def manifestMergerVersion: T[String] = Task.Input {
    Versions.manifestMergerVersion
  }

  /**
   * Specifies the version of the Android build tools to be used.
   */
  def buildToolsVersion: T[String]

  /**
   * Specifies the version of the Android NDK (Native Development Kit) to be used.
   */
  def ndkVersion: T[String] = Task.Input {
    Versions.ndkVersion
  }

  /**
   * Specifies the version of CMake to be used.
   */
  def cmakeVersion: T[String] = Task.Input {
    Versions.cmakeVersion
  }

  /**
   * Specifies the Android platform version (e.g., Android API level).
   */
  def platformsVersion: T[String] = Task { "android-" + buildToolsVersion().split('.').head }

  /**
   * Specifies the version of the Command Line Tools to be used.
   */
  def cmdlineToolsVersion: T[String] = Task {
    millCmdlineToolsVersion()
  }

  /**
   * URL to download bundle tool, used for creating Android app bundles (AAB files).
   */
  def bundleToolUrl: T[String] = Task {
    s"https://github.com/google/bundletool/releases/download/${bundleToolVersion()}/bundletool-all-${bundleToolVersion()}.jar"
  }

  /**
   * Provides the path to the `bundleTool.jar` file, necessary for creating Android bundles.
   *
   * For More Read Bundle Tool [[https://developer.android.com/tools/bundletool Documentation]]
   */
  def bundleToolPath: T[PathRef] = Task() {
    val url = bundleToolUrl()
    // TODO: Use caching API once available, https://github.com/com-lihaoyi/mill/issues/3930
    val cache = FileCache()
      .pipe { cache =>
        if (Task.offline) cache.withCachePolicies(Seq(LocalOnly)) else cache
      }
    cache.logger.use(cache.file(Artifact(url)).run).unsafeRun()(using cache.ec) match {
      case Right(file) =>
        PathRef(os.Path(file)).withRevalidateOnce
      case Left(_) if Task.offline =>
        Task.fail(s"Can't fetch bundle tools (from ${url}) while in offline mode.")
      case Left(ex) =>
        Task.fail(ex.getMessage())

    }
  }

  def remoteReposInfo: Task[PathRef] = Task.Anon {
    val repositoryFile = Task.dest / "repository.xml"
    if (Task.offline) Task.fail("Can't fetch remote repositories in offline mode.")
    else {
      // shouldn't be persistent, allow it to be re-downloaded again.
      // it will be called only if some packages are not installed.
      os.write.over(
        repositoryFile,
        requests.get(remotePackagesUrl).bytes,
        createFolders = true
      )
      PathRef(repositoryFile)
    }
  }

  /**
   * Installs the necessary Android SDK components such as platform-tools, build-tools, and Android platforms
   * via the [[androidSdkManagerModule]] that handles parallel installations (in Mill's context).
   *
   * For more details on the `sdkmanager` tool, refer to:
   * [[https://developer.android.com/tools/sdkmanager sdkmanager Documentation]]
   */
  def androidSdk: T[AndroidSdkComponents] = Task {
    androidSdkManagerModule().androidSdk(
      sdkPath = sdkPath,
      cmdlineToolsComponents = cmdlineTools,
      buildToolsVersion = buildToolsVersion,
      platformsVersion = platformsVersion,
      remoteReposInfo = remoteReposInfo,
      autoAcceptLicenses = autoAcceptLicenses
    )()
  }

  /**
   * Provides the path for the Cmdline Tools, which is essential for managing Android SDK components
   * via the [[androidSdkManagerModule]] that handles downloading of the tools if they are missing.
   */
  def cmdlineTools: T[CmdlineToolsComponents] = Task {
    androidSdkManagerModule().cmdlineTools(
      sdkPath = sdkPath,
      cmdlineToolsVersion = cmdlineToolsVersion,
      remoteReposInfo = remoteReposInfo,
      autoAcceptLicenses = autoAcceptLicenses
    )()
  }

  /**
   * Provides all the Android libraries classpaths, including `android.jar` and other necessary files,
   * for the Android R8 tool.
   */
  def androidLibsClasspaths: T[Seq[PathRef]] = Task {
    androidSdk().libs
  }

  /**
   * Provides the path to the `android.jar` file, necessary for compiling Android apps.
   */
  def androidJarPath: T[PathRef] = Task {
    androidSdk().androidJar
  }

  /**
   * Provides path to the Android CLI lint tool.
   */
  def lintExe: T[PathRef] = Task {
    cmdlineTools().lintExe
  }

  /**
   * Provides path to D8 Dex compiler, used for converting Java bytecode into Dalvik bytecode.
   *
   * For More Read D8 [[https://developer.android.com/tools/d8 Documentation]]
   */
  def d8Exe: T[PathRef] = Task {
    androidSdk().d8Exe
  }

  /**
   * Provides the path to AAPT2, used for resource handling and APK packaging.
   *
   * For More Read AAPT2 [[https://developer.android.com/tools/aapt2 Documentation]]
   */
  def aapt2Exe: T[PathRef] = Task {
    androidSdk().aapt2Exe
  }

  /**
   * Provides the path to the Zipalign tool, which optimizes APK files by aligning their data.
   *
   * For More Read Zipalign [[https://developer.android.com/tools/zipalign Documentation]]
   */
  def zipalignExe: T[PathRef] = Task {
    androidSdk().zipalignExe
  }

  def fontsPath: T[os.Path] = Task {
    sdkPath() / "fonts"
  }

  /**
   * Provides the path to the APK signer tool, used to digitally sign APKs.
   *
   * For More Read APK Signer [[https://developer.android.com/tools/apksigner Documentation]]
   */
  def apksignerExe: T[PathRef] = Task {
    androidSdk().apksignerExe
  }

  /**
   * Provides the path for the Android Debug Bridge (adt) tool.
   *
   * For more information, refer to the official Android documentation [[https://developer.android.com/tools/adb]]
   */
  def adbExe: T[PathRef] = Task {
    androidSdk().adbExe
  }

  /**
   * Provides the path for the Android Virtual Device Manager (avdmanager) tool
   *
   *  For more information refer to the official Android documentation [[https://developer.android.com/tools/avdmanager]]
   */
  def avdmanagerExe: T[PathRef] = Task {
    cmdlineTools().avdmanagerExe
  }

  /**
   * Provides the path for the android emulator tool
   *
   * For more information refer to [[https://developer.android.com/studio/run/emulator]]
   */
  def emulatorExe: T[PathRef] = Task {
    androidSdkManagerModule().androidInstallEmulator(
      sdkPath,
      cmdlineTools
    )()
  }

  /**
   * Location of the default proguard optimisation config.
   * See also [[https://developer.android.com/build/shrink-code]]
   */
  def androidProguardPath: T[os.Path] = Task {
    androidSdk().proguardPath
  }

  /**
   * Provides the path for the r8 tool, used for code shrinking and optimization.
   *
   * @return A task containing a [[PathRef]] pointing to the r8 directory.
   */
  def r8Exe: T[PathRef] = Task {
    cmdlineTools().r8Exe
  }

  def androidNdk: T[AndroidNdkComponents] = Task {
    androidSdkManagerModule().androidNdk(
      sdkPath = sdkPath,
      cmdlineToolsComponents = cmdlineTools,
      ndkVersion = ndkVersion,
      cmakeVersion = cmakeVersion
    )()
  }

  def ndkPath: T[os.Path] = Task {
    androidNdk().ndkPath
  }

  def ninjaExe: T[PathRef] = Task {
    androidNdk().ninjaExe
  }

  def cmakeExe: T[PathRef] = Task {
    androidNdk().cmakeExe
  }

  def cmakeToolchainFilePath: T[PathRef] = Task {
    androidNdk().cmakeToolchainPath
  }

  def androidSdkManagerModule: ModuleRef[AndroidSdkManagerModule] =
    ModuleRef(AndroidSdkManagerModule)

  /**
   * Provides the path for the Android SDK Manager tool
   * @return A task containing a [[PathRef]] pointing to the SDK directory.
   */
  def sdkManagerExe: T[PathRef] = Task {
    cmdlineTools().sdkmanagerExe
  }

  def autoAcceptLicenses: T[Boolean] = Task.Input {
    // Automatically accept licenses in CI environments
    isCI(Task.env)
  }

  private def isCI(env: Map[String, String]): Boolean = {
    val ciEnvironments = Seq(
      "CI",
      "CONTINUOUS_INTEGRATION",
      "JENKINS_URL",
      "TRAVIS",
      "CIRCLECI",
      "GITHUB_ACTIONS",
      "GITLAB_CI",
      "BITBUCKET_PIPELINE",
      "TEAMCITY_VERSION"
    )
    ciEnvironments.exists(key => env.contains(key))
  }

  private def sdkPath: T[os.Path] = Task.Input {
    Task.env.get("ANDROID_HOME")
      .orElse(Task.env.get("ANDROID_SDK_ROOT")) match {
      case Some(x) => os.Path(x)
      case _ => throw new IllegalStateException("Android SDK location not found. Define a valid" +
          " SDK location with an ANDROID_HOME environment variable.")
    }
  }
}

object AndroidSdkModule {

  /**
   * Declaration of the Maven Google Repository.
   */
  val mavenGoogle: MavenRepository = MavenRepository("https://maven.google.com/")

}
