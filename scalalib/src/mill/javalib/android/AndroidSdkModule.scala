package mill.javalib.android

import mill._

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

  // this has a format `repository2-%d`, where the last number is schema version. For the needs of this module it
  // is okay to stick with the particular version.
  private val remotePackagesUrl = "https://dl.google.com/android/repository/repository2-3.xml"

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
    installAndroidSdkComponents()
    PathRef(sdkPath().path / "platforms" / platformsVersion() / "android.jar")
  }

  /**
   * Provides path to the Android build tools for the selected version.
   */
  def buildToolsPath: T[PathRef] = Task {
    installAndroidSdkComponents()
    PathRef(sdkPath().path / "build-tools" / buildToolsVersion())
  }

  /**
   * Provides path to the Android cmdline tools for the selected version.
   */
  def cmdlineToolsPath: T[PathRef] = Task {
    installAndroidSdkComponents()
    PathRef(sdkPath().path / "cmdline-tools" / "latest" / "bin")
  }

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
   * Installs the necessary Android SDK components such as platform-tools, build-tools, and Android platforms.
   *
   * For more details on the `sdkmanager` tool, refer to:
   * [[https://developer.android.com/tools/sdkmanager sdkmanager Documentation]]
   *
   * @return A task containing a [[PathRef]] pointing to the SDK directory.
   */
  def installAndroidSdkComponents: T[Unit] = Task {
    val sdkPath0 = sdkPath()
    val sdkManagerPath = findLatestSdkManager(sdkPath0.path) match {
      case Some(x) => x
      case _ => throw new IllegalStateException(
          s"Cannot locate cmdline-tools in Android SDK $sdkPath0. Download" +
            " it at https://developer.android.com/studio#command-tools. See https://developer.android.com/tools" +
            " for more details."
        )
    }

    val packages = Seq(
      "platform-tools",
      s"build-tools;${buildToolsVersion()}",
      s"platforms;${platformsVersion()}"
    )
    // sdkmanager executable and state of the installed package is a shared resource, which can be accessed
    // from the different Android SDK modules.
    AndroidSdkLock.synchronized {
      val missingPackages = packages.filter(p => !isPackageInstalled(sdkPath0.path, p))
      val packagesWithoutLicense = missingPackages
        .map(p => (p, isLicenseAccepted(sdkPath0.path, remoteReposInfo().path, p)))
        .filter(!_._2)
      if (packagesWithoutLicense.nonEmpty) {
        throw new IllegalStateException(
          "Failed to install the following SDK packages, because their respective" +
            s" licenses are not accepted:\n\n${packagesWithoutLicense.map(_._1).mkString("\n")}"
        )
      }

      if (missingPackages.nonEmpty) {
        val callResult = os.call(
          // Install platform-tools, build-tools, and the Android platform
          Seq(sdkManagerPath.toString) ++ missingPackages,
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

  private def sdkPath: T[PathRef] = Task {
    T.env.get("ANDROID_HOME")
      .orElse(T.env.get("ANDROID_SDK_ROOT")) match {
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

  private def remoteReposInfo: Command[PathRef] = Task.Command {
    // shouldn't be persistent, allow it to be re-downloaded again.
    // it will be called only if some packages are not installed.
    val path = T.dest / "repository.xml"
    os.write(
      T.dest / "repository.xml",
      requests.get(remotePackagesUrl).bytes
    )
    PathRef(path)
  }

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))

  private def findLatestSdkManager(sdkPath: os.Path): Option[os.Path] = {
    var sdkManagerPath = sdkPath / "cmdline-tools/latest/bin/sdkmanager"
    if (!os.exists(sdkManagerPath)) {
      // overall it can be cmdline-tools/<version>
      val candidates = os.list(sdkPath / "cmdline-tools")
        .filter(os.isDir)
      if (candidates.nonEmpty) {
        val latestCmdlineToolsPath = candidates
          .map(p => (p, p.baseName.split('.')))
          .filter(_._2 match {
            case Array(_, _) => true
            case _ => false
          })
          .maxBy(_._2.head.toInt)._1
        sdkManagerPath = latestCmdlineToolsPath / "bin" / "sdkmanager"
      }
    }
    Some(sdkManagerPath).filter(os.exists)
  }
}

private object AndroidSdkLock
