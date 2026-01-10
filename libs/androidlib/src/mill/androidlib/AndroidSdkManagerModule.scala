package mill.androidlib

import mill.api.*
import mill.api.JsonFormatters.pathReadWrite
import mill.client.lock.{Lock, TryLocked}
import os.CommandResult
import upickle.ReadWriter

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import scala.util.Properties.{isLinux, isMac, isWin}
import scala.util.boundary
import scala.xml.XML

/**
 * A worker that executes one thing at a time, using the provided lock.
 *
 * TODO handle channels (i.e. android canary channel)
 * TODO explicit sdk root to sdkmanager via --sdk_root
 */
@mill.api.experimental
trait AndroidSdkManagerModule extends ExternalModule {

  def androidMillHomeDir(): os.Path = {
    val globalDebugFileLocation = os.home / ".mill-android"
    if (!os.exists(globalDebugFileLocation))
      os.makeDir.all(globalDebugFileLocation)
    globalDebugFileLocation
  }

  def androidSdkManagerLockFile: os.Path = androidMillHomeDir() / ".sdkmanager.lock"

  def androidSdkManagerWorkerMaxWaitAttempts: Task[Int] = Task {
    25
  }

  def androidSdkManagerWorker: Task.Worker[AndroidSdkManagerWorker] = Task.Worker {
    new AndroidSdkManagerWorker(androidMillHomeDir(), androidSdkManagerWorkerMaxWaitAttempts())
  }

  /**
   * Provides the correct path to the sdkmanager executable based on the OS.
   */
  private def sdkManagerExePath(cmdlineToolsPath: os.Path): os.Path = {
    val ext = if (isWin) ".bat" else ""
    cmdlineToolsPath / "bin" / s"sdkmanager$ext"
  }

  private def cmdlineToolsURL(versionLong: String): String = {
    val platform =
      if (isWin) "win"
      else if (isMac) "mac"
      else if (isLinux) "linux"
      else throw new IllegalStateException("Unknown platform")

    s"https://dl.google.com/android/repository/commandlinetools-$platform-${versionLong}_latest.zip"
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

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))

  private def acceptLicenses(sdkManagerExePath: os.Path) = {
    val args = if (isWin)
      Seq("cmd", "/c", "(for /l %i in (1,1,10) do @echo y)")
    else
      Seq("echo", "y\n" * 10)
    os.proc(
      args
    ).pipeTo(os.proc(sdkManagerExePath.toString, "--licenses")).call(stdout = os.Pipe)
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

  /**
   * Provides the path for the Cmdline Tools, which is essential for managing Android SDK components.
   * Downloads if missing.
   *
   * @return A task containing a [[PathRef]] pointing to the SDK directory.
   */
  def cmdlineTools(
      sdkPath: Task[os.Path],
      cmdlineToolsVersion: Task[String],
      remoteReposInfo: Task[PathRef],
      autoAcceptLicenses: Task[Boolean]
  ): Task[CmdlineToolsComponents] = Task.Anon {
    androidSdkManagerWorker().processInFunnel { () =>
      val cmdlineToolsVersionShort = cmdlineToolsVersion()
      val basePath = sdkPath() / "cmdline-tools" / cmdlineToolsVersionShort
      val sdkmanagerPath = sdkManagerExePath(basePath)

      if (!os.exists(basePath)) {
        Task.log.info(
          s"Cmdline tools version $cmdlineToolsVersionShort not found in ${sdkmanagerPath}. Downloading and installing, this may take a while..."
        )
        installCmdlineToolsUnsafe(
          sdkPath(),
          cmdlineToolsVersion(),
          cmdlineToolsVersionShort,
          remoteReposInfo().path,
          Task.dest,
          autoAcceptLicenses()
        )
      } else if (!os.exists(sdkmanagerPath)) {
        throw new IllegalStateException(
          s"$basePath exists but is not setup correctly. " +
            "Please remove it and retry or fix the installation manually (e.g. via Android Studio)."
        )
      }

      CmdlineToolsComponents(
        basePath = basePath,
        avdmanagerExe = toolPathRef(basePath / "bin/avdmanager"),
        r8Exe = toolPathRef(basePath / "bin/r8"),
        sdkmanagerExe = toolPathRef(sdkmanagerPath),
        lintExe = toolPathRef(basePath / "bin/lint")
      )
    }
  }

  private def installCmdlineToolsUnsafe(
      sdkPath: os.Path,
      millVersionShort: String,
      versionShort: String,
      remoteReposInfo: os.Path,
      destination: os.Path,
      autoAcceptLicenses: Boolean
  ) = {

    val millCmdlineToolsPath = sdkPath / "cmdline-tools" / millVersionShort
    val millSdkManagerExe = sdkManagerExePath(millCmdlineToolsPath)
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
      androidSdkManagerInstallUnsafe(millSdkManagerExe, Seq(s"cmdline-tools;$versionShort"))
    }
  }

  /**
   * Installs the emulator with the provider sdkManager
   */
  def androidInstallEmulator(
      sdkPath: Task[os.Path],
      cmdlineToolsComponents: Task[CmdlineToolsComponents]
  ): Task[PathRef] = Task.Anon {
    androidSdkManagerInstall(
      Task.Anon(cmdlineToolsComponents().sdkmanagerExe),
      Task.Anon(Seq("emulator"))
    )
    toolPathRef(sdkPath() / "emulator/emulator")
  }

  private def toolPathRef(path: os.Path)(using TaskCtx): PathRef = {
    if (os.exists(path)) {
      PathRef(path).withRevalidateOnce
    } else boundary {
      val winExts = Seq("exe", "bat")
      if (isWin && !winExts.contains(path.ext)) {
        winExts.foreach { ext =>
          // try to find the tool with extension
          val winPath = os.Path(s"${path.toString}.$ext")
          if (os.exists(winPath)) {
            boundary.break(PathRef(winPath).withRevalidateOnce)
          }
        }
      }
      Task.fail(s"Tool at path ${path} does not exist")
    }
  }

  /**
   * Installs the necessary Android SDK components such as platform-tools, build-tools, and Android platforms.
   *
   * For more details on the `sdkmanager` tool, refer to:
   * [[https://developer.android.com/tools/sdkmanager sdkmanager Documentation]]
   */

  def androidSdk(
      sdkPath: Task[os.Path],
      cmdlineToolsComponents: Task[CmdlineToolsComponents],
      buildToolsVersion: Task[String],
      platformsVersion: Task[String],
      remoteReposInfo: Task[PathRef],
      autoAcceptLicenses: Task[Boolean]
  ): Task[AndroidSdkComponents] = Task.Anon {
    androidSdkManagerWorker().processInFunnel { () =>

      val packages = Seq(
        "platform-tools", // adb
        s"build-tools;${buildToolsVersion()}",
        s"platforms;${platformsVersion()}",
        "tools" // proguard
      )

      val sdkManagerPath = cmdlineToolsComponents().sdkmanagerExe.path

      // sdkmanager executable and state of the installed package is a shared resource, which can be accessed
      // from the different Android SDK modules.
      val missingPackages = packages.filter(p => !isPackageInstalled(sdkPath(), p))
      Task.log.info(s"Found ${missingPackages} missing packages...")
      val packagesWithoutLicense = missingPackages
        .map(p => (p, isLicenseAccepted(sdkPath(), remoteReposInfo().path, p)))
        .filter(!_._2)
      if (packagesWithoutLicense.nonEmpty) {
        if (autoAcceptLicenses()) {
          acceptLicenses(sdkManagerPath)
        } else {
          Task.fail(
            "Failed to install the following SDK packages, because their respective" +
              s" licenses are not accepted:\n\n${packagesWithoutLicense.map(_._1).mkString("\n")}" +
              s"\nPlease run `${sdkManagerPath.toString} --licenses` to review and accept the licenses" +
              ", or override `autoAcceptLicenses` to `true`."
          )
        }
      }

      if (missingPackages.nonEmpty) {
        val callResult = androidSdkManagerInstallUnsafe(sdkManagerPath, missingPackages)

        if (callResult.exitCode != 0) {
          Task.fail(
            "Failed to install Android SDK components. Check logs for more details."
          )
        }
      }

      val androidJar = toolPathRef(sdkPath() / "platforms" / platformsVersion() / "android.jar")
      val libs = Seq(
        os.sub / "core-for-system-modules.jar",
        os.sub / "optional" / "org.apache.http.legacy.jar",
        os.sub / "optional" / "android.car.jar",
        os.sub / "optional" / "android.test.mock.jar",
        os.sub / "optional" / "android.test.base.jar",
        os.sub / "optional" / "android.test.runner.jar"
      )
        .map(p => sdkPath() / "platforms" / platformsVersion() / p)
        .map(p => PathRef(p).withRevalidateOnce)

      val buildToolsPath = sdkPath() / "build-tools" / buildToolsVersion()

      AndroidSdkComponents(
        sdkPath = sdkPath(),
        androidJar = androidJar,
        libs = Seq(androidJar) ++ libs,
        buildToolsPath = buildToolsPath,
        d8Exe = toolPathRef(buildToolsPath / "d8"),
        aapt2Exe = toolPathRef(buildToolsPath / "aapt2"),
        zipalignExe = toolPathRef(buildToolsPath / "zipalign"),
        apksignerExe = toolPathRef(buildToolsPath / "apksigner"),
        adbExe = toolPathRef(sdkPath() / "platform-tools/adb"),
        proguardPath = sdkPath() / "tools/proguard"
      )
    }
  }

  /**
   * Install the Android NDK (Native Development Kit) for building native code.
   */
  def androidNdk(
      sdkPath: Task[os.Path],
      cmdlineToolsComponents: Task[CmdlineToolsComponents],
      ndkVersion: Task[String],
      cmakeVersion: Task[String]
  ): Task[AndroidNdkComponents] = Task.Anon {
    val packages = Seq(
      s"ndk;${ndkVersion()}",
      s"cmake;${cmakeVersion()}"
    )
    androidSdkManagerInstall(Task.Anon(cmdlineToolsComponents().sdkmanagerExe), Task.Anon(packages))

    val ndkPath = sdkPath() / "ndk" / ndkVersion()

    AndroidNdkComponents(
      ndkVersion = ndkVersion(),
      ndkPath = ndkPath,
      ninjaExe = toolPathRef(sdkPath() / "cmake" / cmakeVersion() / "bin/ninja"),
      cmakeExe = toolPathRef(sdkPath() / "cmake" / cmakeVersion() / "bin/cmake"),
      cmakeToolchainPath = toolPathRef(ndkPath / "build" / "cmake" / "android.toolchain.cmake")
    )
  }

  def androidSdkManagerInstall(
      sdkmanagerExe: Task[PathRef],
      packages: Task[Seq[String]]
  ): Task[CommandResult] = Task.Anon {
    androidSdkManagerWorker().processInFunnel { () =>
      androidSdkManagerInstallUnsafe(sdkmanagerExe().path, packages())
    }
  }

  private def androidSdkManagerInstallUnsafe(
      sdkmanagerExe: os.Path,
      packages: Seq[String]
  ): CommandResult = {
    os.call(
      cmd = Seq(
        sdkmanagerExe.toString,
        "--install"
      ) ++ packages,
      stdout = os.Inherit,
      stderr = os.Inherit
    )
  }

}

object AndroidSdkManagerModule extends AndroidSdkManagerModule {
  lazy val millDiscover = Discover[this.type]
}

/**
 * Worker used by the AndroidSdkManagerModule (ExternalModule)
 * that has a "funnerl" (single thread executor) and ensures
 * only one thread access at a time for sdkmanager purposes
 * and a file lock to ensure only one Mill process at a time
 * accesses it
 */
private class AndroidSdkManagerWorker(androidHome: os.Path, maxAttempts: Int)
    extends AutoCloseable {

  private val workerThread = Executors.newSingleThreadExecutor()
  private val androidSdkManagerLockFile = androidHome / ".sdkmanager.lock"

  private val lock = Lock.file(androidSdkManagerLockFile.toString)

  private def acquireLock(using ctx: mill.api.TaskCtx): TryLocked = {
    var attempts = 0
    var tryLock: TryLocked = lock.tryLock()
    while (!tryLock.isLocked) {
      tryLock.close()
      attempts += 1
      if (attempts > maxAttempts)
        Task.fail(
          s"Giving up waiting for sdkmanager. Please check if the process is slow or stuck or if the ${androidSdkManagerLockFile} is held by another process!"
        )
      Task.log.info(
        s"Waiting for sdkmanager lock ($attempts/$maxAttempts)... Trying again in 5 seconds!"
      )
      Thread.sleep(5000)
      tryLock = lock.tryLock()
    }
    tryLock
  }

  def processInFunnel[A](f: () => A)(using TaskCtx): A = {
    workerThread.submit(() => scala.util.Using.resource(acquireLock)(_ => f())).get()
  }

  override def close(): Unit = {
    scala.util.Try(workerThread.shutdown())
    scala.util.Try(lock.delete())
  }
}

case class CmdlineToolsComponents(
    basePath: os.Path,
    avdmanagerExe: PathRef,
    r8Exe: PathRef,
    sdkmanagerExe: PathRef,
    lintExe: PathRef
) derives ReadWriter

case class AndroidSdkComponents(
    sdkPath: os.Path,
    androidJar: PathRef,
    libs: Seq[PathRef],
    buildToolsPath: os.Path,
    d8Exe: PathRef,
    aapt2Exe: PathRef,
    zipalignExe: PathRef,
    apksignerExe: PathRef,
    adbExe: PathRef,
    proguardPath: os.Path
) derives ReadWriter

case class AndroidNdkComponents(
    ndkVersion: String,
    ndkPath: os.Path,
    ninjaExe: PathRef,
    cmakeExe: PathRef,
    cmakeToolchainPath: PathRef
) derives ReadWriter
