package mill.androidlib

import coursier.MavenRepository
import coursier.cache.CachePolicy.LocalOnly
import coursier.cache.FileCache
import coursier.util.Artifact
import mill.*
import mill.api.{Result, TaskCtx}
import mill.androidlib.Versions

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.chaining.given
import scala.xml.XML

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

  import AndroidSdkModule._

  // this has a format `repository2-%d`, where the last number is schema version. For the needs of this module it
  // is okay to stick with the particular version.
  private val remotePackagesUrl = "https://dl.google.com/android/repository/repository2-3.xml"

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
   * Specifies the version of the internal Command Line Tools to be used.
   */
  private def millCmdlineToolsVersion: T[String] = Task.Input {
    Versions.millCmdlineToolsVersion
  }

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

  /**
   * Provides all the Android libraries classpaths, including `android.jar` and other necessary files,
   * for the Android R8 tool.
   */
  def androidLibsClasspaths: T[Seq[PathRef]] = {
    val libs = Seq(
      os.sub / "android.jar",
      os.sub / "core-for-system-modules.jar",
      os.sub / "optional" / "org.apache.http.legacy.jar",
      os.sub / "optional" / "android.car.jar",
      os.sub / "optional" / "android.test.mock.jar",
      os.sub / "optional" / "android.test.base.jar",
      os.sub / "optional" / "android.test.runner.jar"
    )
    Task {
      installAndroidSdkComponents()
      Task.traverse(libs)(p =>
        Task.Anon { toolPathRef(sdkPath().path / "platforms" / platformsVersion() / p) }
      )()
    }
  }

  /**
   * Provides the path to the `android.jar` file, necessary for compiling Android apps.
   */
  def androidJarPath: T[PathRef] = Task {
    installAndroidSdkComponents()
    toolPathRef(sdkPath().path / "platforms" / platformsVersion() / "android.jar")
  }

  /**
   * Provides path to the Android build tools for the selected version.
   */
  def buildToolsPath: T[PathRef] = Task {
    installAndroidSdkComponents()
    toolPathRef(sdkPath().path / "build-tools" / buildToolsVersion())
  }

  /**
   * Provides path to the Android CLI lint tool.
   */
  def lintToolPath: T[PathRef] = Task {
    installAndroidSdkComponents()
    toolPathRef(cmdlineToolsPath().path / "bin/lint")
  }

  /**
   * Provides path to D8 Dex compiler, used for converting Java bytecode into Dalvik bytecode.
   *
   * For More Read D8 [[https://developer.android.com/tools/d8 Documentation]]
   */
  def d8Path: T[PathRef] = Task {
    toolPathRef(buildToolsPath().path / "d8")
  }

  /**
   * Provides the path to AAPT2, used for resource handling and APK packaging.
   *
   * For More Read AAPT2 [[https://developer.android.com/tools/aapt2 Documentation]]
   */
  def aapt2Path: T[PathRef] = Task {
    toolPathRef(buildToolsPath().path / "aapt2")
  }

  /**
   * Provides the path to the Zipalign tool, which optimizes APK files by aligning their data.
   *
   * For More Read Zipalign [[https://developer.android.com/tools/zipalign Documentation]]
   */
  def zipalignPath: T[PathRef] = Task {
    toolPathRef(buildToolsPath().path / "zipalign")
  }

  def fontsPath: T[PathRef] = Task {
    toolPathRef(sdkPath().path / "fonts")
  }

  /**
   * Provides the path to the APK signer tool, used to digitally sign APKs.
   *
   * For More Read APK Signer [[https://developer.android.com/tools/apksigner Documentation]]
   */
  def apksignerPath: T[PathRef] = Task {
    toolPathRef(buildToolsPath().path / "apksigner")
  }

  /**
   * Provides the path for the Android Debug Bridge (adt) tool.
   *
   * For more information, refer to the official Android documentation [[https://developer.android.com/tools/adb]]
   */
  def adbPath: T[PathRef] = Task {
    toolPathRef(sdkPath().path / "platform-tools/adb")
  }

  /**
   * Provides the path for the Android Virtual Device Manager (avdmanager) tool
   *
   *  For more information refer to the official Android documentation [[https://developer.android.com/tools/avdmanager]]
   */
  def avdPath: T[PathRef] = Task {
    toolPathRef(cmdlineToolsPath().path / "bin/avdmanager")
  }

  /**
   * Provides the path for the android emulator tool
   *
   * For more information refer to [[https://developer.android.com/studio/run/emulator]]
   */
  def emulatorPath: T[PathRef] = Task {
    toolPathRef(sdkPath().path / "emulator/emulator")
  }

  /**
   * Location of the default proguard optimisation config.
   * See also [[https://developer.android.com/build/shrink-code]]
   */
  def androidProguardPath: T[PathRef] = Task {
    toolPathRef(sdkPath().path / "tools/proguard")
  }

  /**
   * Provides the path for the r8 tool, used for code shrinking and optimization.
   *
   * @return A task containing a [[PathRef]] pointing to the r8 directory.
   */
  def r8Exe: T[PathRef] = Task {
    toolPathRef(cmdlineToolsPath().path / "bin/r8")
  }

  def ndkPath: T[PathRef] = Task {
    installAndroidNdk()
    toolPathRef(sdkPath().path / "ndk" / ndkVersion())
  }

  def ninjaPath: T[PathRef] = Task {
    installAndroidNdk()
    toolPathRef(sdkPath().path / "cmake" / cmakeVersion() / "bin" / "ninja")
  }

  def cmakePath: T[PathRef] = Task {
    installAndroidNdk()
    toolPathRef(sdkPath().path / "cmake" / cmakeVersion() / "bin" / "cmake")
  }

  def cmakeToolchainFilePath: T[PathRef] = Task {
    installAndroidNdk()
    toolPathRef(ndkPath().path / "build" / "cmake" / "android.toolchain.cmake")
  }

  def autoAcceptLicenses: T[Boolean] = Task {
    // Automatically accept licenses in CI environments
    isCI
  }

  private def acceptLicenses(sdkManagerExePath: os.Path) = {
    // Use `echo` to ensure compatibility with Windows environments
    os.proc(
      "echo",
      "y\n" * 10
    ).pipeTo(os.proc(sdkManagerExePath.toString, "--licenses")).call()
  }

  private def isCI: Boolean = {
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
    ciEnvironments.exists(env => sys.env.contains(env))
  }

  // TODO: Replace hardcoded mapping with automated parsing
  // of [[remoteReposInfo]]
  private def cmdlineToolsShortToLong(versionShort: String): String = {
    versionShort match {
      case "7.0" => "8512546"
      case "8.0" => "9123335"
      case "9.0" => "9477386"
      case "10.0" => "9862592"
      case "11.0" => "10406996"
      case "12.0" => "11076708"
      case "13.0" => "11479570"
      case "16.0" => "12266719"
      case "17.0" => "12700392"
      case "19.0" => "13114758"
      case _ =>
        throw new IllegalArgumentException(s"Unsupported cmdline tools version: $versionShort")
    }
  }

  private def cmdlineToolsURL(versionLong: String): String = {
    val osName: Option[String] = sys.props.get("os.name").map(_.toLowerCase)

    val platform = Seq("linux", "mac", "windows").find(osName.contains) match {
      case Some(p) => p
      case None =>
        throw new IllegalStateException(s"Unsupported platform for cmdline tools: $osName")
    }

    s"https://dl.google.com/android/repository/commandlinetools-$platform-${versionLong}_latest.zip"
  }

  private def installCmdlineTools(
      sdkPath: os.Path,
      millVersionShort: String,
      versionShort: String,
      remoteReposInfo: os.Path,
      destination: os.Path,
      autoAcceptLicenses: Boolean
  ) = {
    val millCmdlineToolsPath = sdkPath / "cmdline-tools" / millVersionShort
    val millSdkManagerExe = millCmdlineToolsPath / "bin" / "sdkmanager"
    if (!os.exists(millSdkManagerExe)) {
      val zipDestination = destination / "cmdline-tools.zip"
      os.write(
        zipDestination,
        requests.get(cmdlineToolsURL(cmdlineToolsShortToLong(millVersionShort)))
      )

      os.unzip(zipDestination, destination)

      // Move the extracted tools to the version-specific directory
      os.move(
        destination / "cmdline-tools",
        millCmdlineToolsPath,
        createFolders = true,
        atomicMove = true
      )
    }
    if (!isLicenseAccepted(sdkPath, remoteReposInfo, s"cmdline-tools;$versionShort")) {
      if (autoAcceptLicenses) {
        acceptLicenses(millSdkManagerExe)
      } else {
        throw new IllegalStateException(
          s"License for cmdline-tools;$versionShort is not accepted. " +
            s"Please run `${millSdkManagerExe.toString} --licenses` to review and accept the licenses" +
            ", or override `autoAcceptLicenses` to `true`."
        )
      }
    }
    if (versionShort != millVersionShort) {
      os.call(
        Seq(
          millSdkManagerExe.toString,
          s"cmdline-tools;$versionShort"
        ),
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }
  }

  /**
   * Provides the path for the Cmdline Tools, which is essential for managing Android SDK components.
   * Downloads if missing.
   * @return A task containing a [[PathRef]] pointing to the SDK directory.
   */
  private def cmdlineToolsPath: Task[PathRef] = Task.Anon {
    AndroidCmdlineToolsLock.synchronized {
      val cmdlineToolsVersionShort = cmdlineToolsVersion()
      val cmdlineToolsPath0 = sdkPath().path / "cmdline-tools" / cmdlineToolsVersionShort
      if (!os.exists(cmdlineToolsPath0)) {
        Task.log.info(
          s"Cmdline tools version $cmdlineToolsVersionShort not found. Downloading and installing, this may take a while..."
        )
        installCmdlineTools(
          sdkPath().path,
          millCmdlineToolsVersion(),
          cmdlineToolsVersionShort,
          remoteReposInfo().path,
          Task.dest,
          autoAcceptLicenses()
        )
      } else if (!os.exists(cmdlineToolsPath0 / "bin" / "sdkmanager")) {
        throw new IllegalStateException(
          s"$cmdlineToolsPath0 exists but is not setup correctly. " +
            "Please remove it and retry or fix the installation manually (e.g. via Android Studio)."
        )
      }

      PathRef(cmdlineToolsPath0)
    }
  }

  /**
   * Provides the path for the Android SDK Manager tool
   * @return A task containing a [[PathRef]] pointing to the SDK directory.
   */
  def sdkManagerPath: Task[PathRef] = Task.Anon {
    PathRef(cmdlineToolsPath().path / "bin" / "sdkmanager")
  }

  /**
   * Installs the necessary Android SDK components such as platform-tools, build-tools, and Android platforms.
   *
   * For more details on the `sdkmanager` tool, refer to:
   * [[https://developer.android.com/tools/sdkmanager sdkmanager Documentation]]
   */
  def installAndroidSdkComponents: Task[Unit] = Task.Anon {
    val sdkPath0 = sdkPath()
    val sdkManagerPath0 = sdkManagerPath().path

    val packages = Seq(
      "platform-tools",
      s"build-tools;${buildToolsVersion()}",
      s"platforms;${platformsVersion()}",
      "tools"
    )
    // sdkmanager executable and state of the installed package is a shared resource, which can be accessed
    // from the different Android SDK modules.
    AndroidSdkLock.synchronized {
      val missingPackages = packages.filter(p => !isPackageInstalled(sdkPath0.path, p))
      val packagesWithoutLicense = missingPackages
        .map(p => (p, isLicenseAccepted(sdkPath0.path, remoteReposInfo().path, p)))
        .filter(!_._2)
      if (packagesWithoutLicense.nonEmpty) {
        if (autoAcceptLicenses()) {
          acceptLicenses(sdkManagerPath0)
        } else {
          throw new IllegalStateException(
            "Failed to install the following SDK packages, because their respective" +
              s" licenses are not accepted:\n\n${packagesWithoutLicense.map(_._1).mkString("\n")}" +
              s"\nPlease run `${sdkManagerPath0.toString} --licenses` to review and accept the licenses" +
              ", or override `autoAcceptLicenses` to `true`."
          )
        }
      }

      if (missingPackages.nonEmpty) {
        val callResult = os.call(
          // Install platform-tools, build-tools, and the Android platform
          Seq(sdkManagerPath0.toString) ++ missingPackages,
          stdout = os.Inherit
        )
        if (callResult.exitCode != 0) {
          throw new IllegalStateException(
            "Failed to install Android SDK components. Check logs for more details."
          )
        }
      }
    }
  }

  /**
   * Install the Android NDK (Native Development Kit) for building native code.
   */
  def installAndroidNdk: T[Unit] = Task {
    installAndroidSdkComponents()

    AndroidNdkLock.synchronized {
      os.call(
        Seq(
          sdkManagerPath().path.toString,
          "--install",
          s"ndk;${ndkVersion()}",
          s"cmake;${cmakeVersion()}"
        )
      )
    }
  }

  private def androidHomeEnv: T[Option[String]] = Task.Input {
    Task.env.get("ANDROID_HOME")
      .orElse(Task.env.get("ANDROID_SDK_ROOT"))
  }

  private def sdkPath: T[PathRef] = Task {
    androidHomeEnv() match {
      case Some(x) => PathRef(os.Path(x))
      case _ => throw new IllegalStateException("Android SDK location not found. Define a valid" +
          " SDK location with an ANDROID_HOME environment variable.")
    }
  }

  private def isPackageInstalled(sdkPath: os.Path, packageName: String): Boolean =
    os.exists(sdkPath / os.SubPath(packageName.replaceAll(";", "/")))

  private def isLicenseAccepted(
      sdkPath: os.Path,
      remoteReposInfo: os.Path,
      packageName: String
  ): Boolean = {
    val (licenseName, licenseHash) = licenseForPackage(remoteReposInfo, packageName)
    val licenseFile = sdkPath / "licenses" / licenseName
    os.exists(licenseFile) && os.isFile(licenseFile) && os.read(licenseFile).contains(licenseHash)
  }

  private def licenseForPackage(remoteReposInfo: os.Path, packageName: String): (String, String) = {
    val repositoryInfo = XML.loadFile(remoteReposInfo.toIO)
    val remotePackage = (repositoryInfo \ "remotePackage")
      .filter(_ \@ "path" == packageName)
      .head
    val licenseName = (remotePackage \ "uses-license").head \@ "ref"
    val licenseText = (repositoryInfo \ "license")
      .filter(_ \@ "id" == licenseName)
      .text
      .replaceAll(
        "(?<=\\s)[ \t]*",
        ""
      ) // remove spaces and tabs preceded by space, tab, or newline.
      .replaceAll("(?<!\n)\n(?!\n)", " ") // replace lone newlines with space
      .replaceAll(" +", " ")
      .trim
    val licenseHash = hexArray(sha1.digest(licenseText.getBytes(StandardCharsets.UTF_8)))
    (licenseName, licenseHash)
  }

  def remoteReposInfo: Task[PathRef] = Task.Anon {
    val repositoryFile = Task.dest / "repository.xml"
    if (Task.offline) Result.Failure("Can't fetch remote repositories in offline mode.")
    else Result.create {
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

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))

}

private object AndroidSdkLock
private object AndroidNdkLock
private object AndroidCmdlineToolsLock

object AndroidSdkModule {

  /**
   * Declaration of the Maven Google Repository.
   */
  val mavenGoogle: MavenRepository = MavenRepository("https://maven.google.com/")

  private def toolPathRef(path: os.Path)(using TaskCtx): PathRef = {
    os.exists(path) match {
      case true => PathRef(path).withRevalidateOnce
      case false => Task.fail(s"Tool at path ${path} does not exist")
    }
  }

}
