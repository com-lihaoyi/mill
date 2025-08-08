package mill.androidlib

import coursier.params.ResolutionParams
import mill.*
import mill.androidlib.keytool.KeytoolModule
import mill.api.Logger
import mill.api.internal.*
import mill.api.daemon.internal.internal
import mill.api.{ModuleRef, PathRef, Task}
import mill.javalib.*
import os.{Path, RelPath, zip}
import upickle.default.*
import scala.concurrent.duration.*

import scala.jdk.OptionConverters.RichOptional
import scala.xml.*
import mill.api.daemon.internal.bsp.BspBuildTarget
import mill.api.daemon.internal.EvaluatorApi
import mill.javalib.testrunner.TestResult

/**
 * Enumeration for Android Lint report formats, providing predefined formats
 * with corresponding flags and file extensions. Includes utility methods
 * for implicit conversions, serialization, and retrieving all supported formats.
 */
object AndroidLintReportFormat extends Enumeration {
  protected case class Format(flag: String, extension: String) extends super.Val {
    override def toString: String = extension
  }

  implicit def valueToFormat(v: Value): Format = v.asInstanceOf[Format]

  val Html: Format = Format("--html", "html")
  val Xml: Format = Format("--xml", "xml")
  val Txt: Format = Format("--text", "txt")
  val Sarif: Format = Format("--sarif", "sarif")

  // Define an implicit ReadWriter for the Format case class
  implicit val formatRW: ReadWriter[Format] = macroRW

  // Optional: Add a method to retrieve all possible values
  val allFormats: List[Format] = List(Html, Xml, Txt, Sarif)
}

/**
 * Trait for building Android applications using the Mill build tool.
 *
 * This trait defines all the necessary steps for building an Android app from Java sources,
 * integrating both Android-specific tasks and generic Java tasks by extending the
 * [[AndroidSdkModule]] (for Android SDK interactions) and [[JavaModule]] (for standard Java tasks).
 *
 * It provides a structured way to handle various steps in the Android app build process,
 * including compiling Java sources, creating DEX files, generating resources, packaging
 * APKs, optimizing, and signing APKs.
 *
 * [[https://developer.android.com/studio Android Studio Documentation]]
 */
@mill.api.experimental
trait AndroidAppModule extends AndroidModule { outer =>

  protected val debugKeyStorePass = "mill-android"
  protected val debugKeyAlias = "mill-android"
  protected val debugKeyPass = "mill-android"

  /**
   *   Every Android module has a namespace,
   *   which is used as the Kotlin or Java package name for its generated R and BuildConfig classes.
   *
   * See more in [[https://developer.android.com/build/configure-app-module#set-namespace]]
   */
  def androidApplicationNamespace: String

  /**
   * In the case of android apps this the [[androidApplicationNamespace]].
   * @return
   */
  override final def androidNamespace: String = androidApplicationNamespace

  /**
   * Android Application Id unique to every android application.
   * See more in [[https://developer.android.com/build/configure-app-module#set-application-id]]
   */
  def androidApplicationId: String

  def androidDebugManifestLocation: T[PathRef] = Task.Source {
    "src/debug/AndroidManifest.xml"
  }

  /**
   * Specifies the file format(s) of the lint report. Available file formats are defined in AndroidLintReportFormat,
   * such as [[AndroidLintReportFormat.Html]], [[AndroidLintReportFormat.Xml]], [[AndroidLintReportFormat.Txt]],
   * and [[AndroidLintReportFormat.Sarif]].
   */
  def androidLintReportFormat: T[Seq[AndroidLintReportFormat.Value]] =
    Task { Seq(AndroidLintReportFormat.Html) }

  /**
   * Specifies the lint configuration XML file path. This allows setting custom lint rules or modifying existing ones.
   */
  def androidLintConfigPath: T[Option[PathRef]] = Task { None }

  /**
   * Specifies the lint baseline XML file path. This allows using a baseline to suppress known lint warnings.
   */
  def androidLintBaselinePath: T[Option[PathRef]] = Task { None }

  /**
   * Determines whether the build should fail if Android Lint detects any issues.
   */
  def androidLintAbortOnError: Boolean = true

  /**
   * Specifies additional arguments for the Android Lint tool.
   * Allows for complete customization of the lint command.
   */
  def androidLintArgs: T[Seq[String]] = Task { Seq.empty[String] }

  @internal
  override def bspCompileClasspath(
      needsToMergeResourcesIntoCompileDest: Boolean
  ) = Task.Anon { (ev: EvaluatorApi) =>
    compileClasspath().map(
      _.path
    ).map(UnresolvedPath.ResolvedPath(_)).map(_.resolve(os.Path(ev.outPathJava))).map(sanitizeUri)
  }

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    baseDirectory = Some((moduleDir / "src/main").toNIO),
    tags = Seq("application")
  )

  /**
   * Collect files from META-INF folder of classes.jar (not META-INF of aar in case of Android library).
   */
  def androidLibsClassesJarMetaInf: T[Seq[PathRef]] = Task {
    // ^ not the best name for the method, but this is to distinguish between META-INF of aar and META-INF
    // of classes.jar included in aar
    compileClasspath()
      .filter(ref =>
        ref.path.ext == "jar" &&
          ref != androidSdkModule().androidJarPath()
      )
      .flatMap(ref => {
        val dest = Task.dest / ref.path.baseName
        os.unzip(ref.path, dest)

        // Fix permissions of unzipped directories
        // `os.walk.stream` doesn't work
        def walkStream(p: os.Path): geny.Generator[os.Path] = {
          if (!os.isDir(p)) geny.Generator()
          else {
            val streamed = os.list.stream(p)
            streamed ++ streamed.flatMap(walkStream)
          }
        }

        for (p <- walkStream(dest) if os.isDir(p)) {
          import java.nio.file.attribute.PosixFilePermission
          val newPerms =
            os.perms(p) + PosixFilePermission.OWNER_READ + PosixFilePermission.OWNER_EXECUTE

          os.perms.set(p, newPerms)
        }

        val lookupPath = dest / "META-INF"
        if (os.exists(lookupPath)) {
          os.walk(lookupPath)
            .filter(os.isFile)
            .filterNot(f => isExcludedFromPackaging(f.relativeTo(lookupPath)))
        } else {
          Seq.empty[os.Path]
        }
      })
      .map(PathRef(_))
      .toSeq
  }

  /**
   * Provides additional files to be included in the APK package.
   * This can be used to add custom files or resources that are not
   * automatically included in the build process like native libraries .so files.
   */
  def androidPackageableExtraFiles: T[Seq[AndroidPackageableExtraFile]] =
    Task { Seq.empty[AndroidPackageableExtraFile] }

  def androidPackageMetaInfoFiles: T[Seq[AndroidPackageableExtraFile]] = Task {
    def metaInfRoot(p: os.Path): os.Path = {
      var current = p
      while (!current.endsWith(os.rel / "META-INF")) {
        current = current / os.up
      }
      current / os.up
    }

    androidLibsClassesJarMetaInf()
      .map(ref =>
        AndroidPackageableExtraFile(
          PathRef(ref.path),
          ref.path.subRelativeTo(metaInfRoot(ref.path))
        )
      ).distinctBy(_.destination)

  }

  /**
   * Packages DEX files and Android resources into an unsigned APK.
   *
   * @return A `PathRef` to the generated unsigned APK file (`app.unsigned.apk`).
   */
  def androidUnsignedApk: T[PathRef] = Task {
    val unsignedApk = Task.dest / "app.unsigned.apk"

    os.copy(androidLinkedResources().path / "apk/res.apk", unsignedApk)
    val dexFiles = os.walk(androidDex().path)
      .filter(_.ext == "dex")
      .map(os.zip.ZipSource.fromPath)

    val metaInf = androidPackageMetaInfoFiles().map(extraFile =>
      os.zip.ZipSource.fromPathTuple((extraFile.source.path, extraFile.destination.asSubPath))
    )

    // add all the extra files to the APK
    val extraFiles: Seq[zip.ZipSource] = androidPackageableExtraFiles().map(extraFile =>
      os.zip.ZipSource.fromPathTuple((extraFile.source.path, extraFile.destination.asSubPath))
    )

    // TODO generate aar-metadata.properties (for lib distribution, not in this module) or
    //  app-metadata.properties (for app distribution).
    // Example of aar-metadata.properties:
    // aarFormatVersion=1.0
    // aarMetadataVersion=1.0
    // minCompileSdk=33
    // minCompileSdkExtension=0
    // minAndroidGradlePluginVersion=1.0.0
    //
    // Example of app-metadata.properties:
    // appMetadataVersion=1.1
    // androidGradlePluginVersion=8.7.2
    os.zip(unsignedApk, dexFiles)
    os.zip(unsignedApk, metaInf)
    os.zip(unsignedApk, extraFiles)

    PathRef(unsignedApk)
  }

  override def androidPackagedDeps: T[Seq[PathRef]] =
    super.androidPackagedDeps() ++ Seq(androidProcessedResources())

  override def androidMergeableManifests: Task[Seq[PathRef]] = Task {
    val debugManifest = Seq(androidDebugManifestLocation()).filter(pr => os.exists(pr.path))
    val libManifests = androidUnpackArchives().flatMap(_.manifest)
    debugManifest ++ libManifests
  }

  override def androidMergedManifestArgs: Task[Seq[String]] = Task.Anon {
    Seq(
      "--main",
      androidManifest().path.toString(),
      "--remove-tools-declarations",
      "--property",
      s"min_sdk_version=${androidMinSdk()}",
      "--property",
      s"target_sdk_version=${androidTargetSdk()}",
      "--property",
      s"version_code=${androidVersionCode()}",
      "--property",
      s"version_name=${androidVersionName()}",
      "--property",
      s"package=${androidApplicationId}",
      "--manifest-placeholders",
      s"applicationId=${androidApplicationId}"
    ) ++ androidMergeableManifests().flatMap(m => Seq("--libs", m.path.toString))
  }

  /**
   * Optimizes the APK using the `zipalign` tool for better performance.
   *
   * For more details on the zipalign tool, refer to:
   * [[https://developer.android.com/tools/zipalign zipalign Documentation]]
   */
  def androidAlignedUnsignedApk: T[PathRef] = Task {
    val alignedApk: os.Path = Task.dest / "app.aligned.apk"

    os.call((
      androidSdkModule().zipalignPath().path,
      "-f",
      "-p",
      "4",
      androidUnsignedApk().path,
      alignedApk
    ))

    PathRef(alignedApk)
  }

  /**
   * Name of the key alias in the release keystore. Default is not set.
   */
  def androidReleaseKeyAlias: T[Option[String]] = Task {
    None
  }

  /**
   * Name of the release keystore file. Default is not set.
   */
  def androidReleaseKeyName: Option[String] = None

  /**
   * Password for the release key. Default is not set.
   */
  def androidReleaseKeyPass: T[Option[String]] = Task {
    None
  }

  /**
   * Password for the release keystore. Default is not set.
   */
  def androidReleaseKeyStorePass: T[Option[String]] = Task {
    None
  }

  /**
   * Saves the private keystore password into a file to be passed to [[androidApk]] via [[androidSignKeyDetails]]
   * as a file parameter without risking exposing the release password in the logs.
   *
   * See more [[https://developer.android.com/tools/apksigner]]
   * @return
   */
  def androidReleaseKeyStorePassFile: T[PathRef] = Task {
    val filePass = Task.dest / "keystore_password.txt"
    val keystorePass = androidReleaseKeyStorePass().getOrElse("")
    os.write(filePass, keystorePass)
    PathRef(filePass)
  }

  /**
   * Saves the private key password into a file to be passed to [[androidApk]] via [[androidSignKeyDetails]]
   * as a file parameter without risking exposing the release password in the logs.
   *
   * See more [[https://developer.android.com/tools/apksigner]]
   *
   * @return
   */
  def androidReleaseKeyPassFile: T[PathRef] = Task {
    val filePass = Task.dest / "key_password.txt"
    val keyPass = androidReleaseKeyPass().getOrElse("")
    os.write(filePass, keyPass)
    PathRef(filePass)
  }

  def androidSignKeyPasswordParams: Task[Seq[String]] = Task.Anon {
    if (androidIsDebug())
      Seq(
        "--ks-pass",
        s"pass:$debugKeyStorePass",
        "--key-pass",
        s"pass:$debugKeyPass"
      )
    else
      Seq(
        "--ks-pass",
        s"file:${androidReleaseKeyStorePassFile().path}",
        "--key-pass",
        s"file:${androidReleaseKeyPassFile().path}"
      )
  }

  /**
   * Generates the command-line arguments required for Android app signing.
   *
   * Uses the release keystore if release build type is set; otherwise, defaults to a generated debug keystore.
   */
  def androidSignKeyDetails: T[Seq[String]] = Task {

    val keyAlias = {
      if (androidIsDebug()) debugKeyAlias else androidReleaseKeyAlias().get
    }

    Seq(
      "--ks",
      androidKeystore().path.toString,
      "--ks-key-alias",
      keyAlias
    ) ++ androidSignKeyPasswordParams()
  }

  /**
   * Signs the APK using a keystore to generate a final, distributable APK.
   *
   * The signing step is mandatory to distribute Android applications. It adds a cryptographic
   * signature to the APK, verifying its authenticity. This method uses the `apksigner` tool
   * along with a keystore file to sign the APK.
   *
   * If no keystore is available, a new one is generated using the `keytool` utility.
   *
   * For more details on the apksigner tool, refer to:
   * [[https://developer.android.com/tools/apksigner apksigner Documentation]]
   */
  def androidApk: T[PathRef] = Task {
    val signedApk = Task.dest / "app.apk"

    val signArgs = Seq(
      androidSdkModule().apksignerPath().path.toString,
      "sign",
      "--in",
      androidAlignedUnsignedApk().path.toString,
      "--out",
      signedApk.toString
    ) ++ androidSignKeyDetails()

    Task.log.info(s"Calling apksigner with arguments: ${signArgs.mkString(" ")}")

    os.call(signArgs)

    PathRef(signedApk)
  }

  /**
   * Runs the Android Lint tool to generate a report on code quality issues.
   *
   * This method utilizes Android Lint, a tool provided by the Android SDK,
   * to analyze the source code for potential bugs, performance issues, and
   * best practices compliance. It generates a report in the specified format.
   *
   * The report is saved in the task's destination directory as "report.html" by
   * default. It can be changed to other file format such as xml, txt and sarif.
   *
   * For more details on the Android Lint tool, refer to:
   * [[https://developer.android.com/studio/write/lint]]
   */
  def androidLintRun(): Command[PathRef] = Task.Command {

    val formats = androidLintReportFormat()

    // Generate the alternating flag and file os.Path strings
    val reportArg: Seq[String] = formats.flatMap { format =>
      Seq(format.flag, (Task.dest / s"report.${format.extension}").toString)
    }

    // Set os.Path to generated `.jar` files and/or `.class` files
    // TODO change to runClasspath once the runtime dependencies + source refs are fixed
    val cp = compileClasspath().map(_.path).filter(os.exists).mkString(":")

    // Set os.Path to the location of the project source codes
    val src = sources().map(_.path).filter(os.exists).mkString(":")

    // Set os.Path to the location of the project resource code
    val res = resources().map(_.path).filter(os.exists).mkString(":")

    // Prepare the lint configuration argument if the config os.Path is set
    val configArg = androidLintConfigPath().map(config =>
      Seq("--config", config.path.toString)
    ).getOrElse(Seq.empty)

    // Prepare the lint baseline argument if the baseline os.Path is set
    val baselineArg = androidLintBaselinePath().map(baseline =>
      Seq("--baseline", baseline.path.toString)
    ).getOrElse(Seq.empty)

    os.call(
      Seq(
        androidSdkModule().lintToolPath().path.toString,
        (moduleDir / "src/main").toString,
        "--classpath",
        cp,
        "--sources",
        src,
        "--resources",
        res
      ) ++ configArg ++ baselineArg ++ reportArg ++ androidLintArgs(),
      check = androidLintAbortOnError
    )

    PathRef(Task.dest)
  }

  /** The name of the virtual device to be created by  [[createAndroidVirtualDevice]] */
  def androidVirtualDeviceIdentifier: String = "test"

  /** The device  id as listed from avdmanager list device. Default is medium_phone */
  def androidDeviceId: String = "medium_phone"

  /**
   * The target architecture of the virtual device to be created by  [[createAndroidVirtualDevice]]
   *  For example, "x86_64" (default). For a list of system images and their architectures,
   *  see the Android SDK Manager `sdkmanager --list`.
   */
  def androidEmulatorArchitecture: String = "x86_64"

  /**
   * Installs the user specified system image for the emulator
   * using sdkmanager . E.g. "system-images;android-35;google_apis_playstore;x86_64"
   */
  def sdkInstallSystemImage(): Command[String] = Task.Command {
    val image =
      s"system-images;${androidSdkModule().platformsVersion()};google_apis_playstore;$androidEmulatorArchitecture"
    Task.log.info(s"Downloading $image")
    val installCall = os.call((
      androidSdkModule().sdkManagerPath().path,
      "--install",
      image
    ))

    if (installCall.exitCode != 0) {
      Task.log.error(
        s"Error trying to install android emulator system image ${installCall.err.text()}"
      )
      throw new Exception(s"Failed to install system image $image: ${installCall.exitCode}")
    }
    image
  }

  /**
   * Creates the android virtual device identified in virtualDeviceIdentifier
   */
  def createAndroidVirtualDevice(): Command[String] = Task.Command(exclusive = true) {
    val command = os.call((
      androidSdkModule().avdPath().path,
      "create",
      "avd",
      "--name",
      androidVirtualDeviceIdentifier,
      "--package",
      sdkInstallSystemImage()(),
      "--device",
      androidDeviceId,
      "--force"
    ))
    if (command.exitCode != 0) {
      Task.log.error(s"Failed to create android virtual device: ${command.err.text()}")
      throw new Exception(s"Failed to create android virtual device: ${command.exitCode}")
    }
    s"DeviceName: $androidVirtualDeviceIdentifier, DeviceId: $androidDeviceId"
  }

  /**
   * Deletes the android device
   */
  def deleteAndroidVirtualDevice: T[os.CommandResult] = Task {
    os.call((
      androidSdkModule().avdPath().path,
      "delete",
      "avd",
      "--name",
      androidVirtualDeviceIdentifier
    ))
  }

  /**
   * Starts the android emulator and waits until it is booted
   *
   * @return The log line that indicates the emulator is ready
   */
  def startAndroidEmulator(): Command[String] = Task.Command(exclusive = true) {
    val ciSettings = Seq(
      "-no-snapshot-save",
      "-no-window",
      "-gpu",
      "swiftshader_indirect",
      "-noaudio",
      "-no-boot-anim",
      "-camera-back",
      "none"
    )
    val settings = if (sys.env.getOrElse("GITHUB_ACTIONS", "false") == "true")
      ciSettings
    else Seq.empty[String]
    val command = Seq(
      androidSdkModule().emulatorPath().path.toString(),
      "-delay-adb",
      "-port",
      androidEmulatorPort
    ) ++ settings ++ Seq("-avd", androidVirtualDeviceIdentifier)

    Task.log.debug(s"Starting emulator with command ${command.mkString(" ")}")

    val startEmuCmd = os.spawn(
      command
    )

    val bootMessage: Option[String] = startEmuCmd.stdout.buffered.lines().filter(l => {
      Task.log.debug(l.trim())
      l.contains("Boot completed in")
    }).findFirst().toScala

    if (bootMessage.isEmpty) {
      throw new Exception(s"Emulator failed to start: ${startEmuCmd.exitCode()}")
    }

    val emulator: String = waitForDevice(androidSdkModule().adbPath(), runningEmulator(), Task.log)

    Task.log.info(s"Emulator ${emulator} started with message $bootMessage")

    bootMessage.get
  }

  def adbDevices(): Command[String] = Task.Command {
    os.call((androidSdkModule().adbPath().path, "devices", "-l")).out.text()
  }

  /**
   * Stops the android emulator
   */
  def stopAndroidEmulator: T[String] = Task {
    val emulator = runningEmulator()
    os.call(
      (androidSdkModule().adbPath().path, "-s", emulator, "emu", "kill")
    )
    emulator
  }

  /** The emulator port where adb connects to. Defaults to 5554 */
  def androidEmulatorPort: String = "5554"

  /**
   * Returns the emulator identifier for created from startAndroidEmulator
   * by iterating the adb device list
   */
  def runningEmulator: T[String] = Task {
    s"emulator-$androidEmulatorPort"
  }

  /**
   * Installs the app to the android device identified by this configuration in [[androidVirtualDeviceIdentifier]].
   *
   * @return The name of the device the app was installed to
   */
  def androidInstall(): Command[String] = Task.Command(exclusive = true) {
    androidInstallTask()
  }

  def androidInstallTask: Task[String] = Task.Anon {
    val emulator = runningEmulator()

    os.call(
      (androidSdkModule().adbPath().path, "-s", emulator, "install", "-r", androidApk().path)
    )

    emulator
  }

  /**
   * Run your application by providing the activity.
   *
   * E.g. `com.package.name.ActivityName`
   *
   * See also [[https://developer.android.com/tools/adb#am]] and [[https://developer.android.com/tools/adb#IntentSpec]]
   * @param activity
   * @return
   */
  def androidRun(activity: String): Command[Vector[String]] = Task.Command(exclusive = true) {
    val emulator = runningEmulator()

    os.call(
      (
        androidSdkModule().adbPath().path,
        "-s",
        emulator,
        "shell",
        "am",
        "start",
        "-n",
        s"${androidApplicationId}/${activity}",
        "-W"
      )
    ).out.lines()
  }

  /**
   * Default os.Path to the keystore file, derived from `androidReleaseKeyName()`.
   * Users can customize the keystore file name to change this path.
   */
  def androidReleaseKeyPath: T[Seq[PathRef]] = {
    val subPaths = androidReleaseKeyName.map(os.sub / _).toSeq
    Task.Sources(subPaths*)
  }

  private def androidMillHomeDir: Task[PathRef] = Task.Anon {
    val globalDebugFileLocation = os.home / ".mill-android"
    if (!os.exists(globalDebugFileLocation))
      os.makeDir(globalDebugFileLocation)
    PathRef(globalDebugFileLocation)
  }

  private def debugKeystoreFile: Task[PathRef] = Task.Anon {
    PathRef(androidMillHomeDir().path / "mill-debug.jks")
  }

  private def keytoolModuleRef: ModuleRef[KeytoolModule] = ModuleRef(KeytoolModule)

  /*
    The debug keystore is stored in `$HOME/.mill-android`. The practical
  purpose of a global keystore is to avoid the user having to uninstall the
  app everytime the task directory is deleted (as the app signatures will not match).
   */
  private def androidDebugKeystore: Task[PathRef] = Task.Anon {
    val debugKeystoreFilePath = debugKeystoreFile().path
    os.makeDir.all(androidMillHomeDir().path)
    keytoolModuleRef().createKeystoreWithCertificate(
      Task.Anon(Seq(
        "--keystore",
        outer.debugKeystoreFile().path.toString,
        "--storepass",
        debugKeyStorePass,
        "--alias",
        debugKeyAlias,
        "--keypass",
        debugKeyPass,
        "--dname",
        s"CN=$debugKeyAlias, OU=$debugKeyAlias, O=$debugKeyAlias, L=$debugKeyAlias, S=$debugKeyAlias, C=$debugKeyAlias",
        "--validity",
        10000.days.toString,
        "--skip-if-exists"
      ))
    )()
    PathRef(debugKeystoreFilePath)
  }

  protected def androidKeystore: T[PathRef] = Task {
    if (androidIsDebug()) androidDebugKeystore()
    else androidReleaseKeyPath().head
  }

  // TODO consider managing with proguard and/or r8
  private def isExcludedFromPackaging(relPath: RelPath): Boolean = {
    val topPath = relPath.segments.head
    // TODO do this better
    // full list is here https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/packaging/PackagingOptionsUtils.kt;drc=85330e2f750acc1e1510623222d80e4b1ad5c8a2
    // but anyway it should be a packaging option DSL to configure additional excludes from the user side
    relPath.ext == "kotlin_module" ||
    relPath.ext == "kotlin_metadata" ||
    relPath.ext == "DSA" ||
    relPath.ext == "EC" ||
    relPath.ext == "SF" ||
    relPath.ext == "RSA" ||
    topPath == "maven" ||
    topPath == "proguard" ||
    topPath == "com.android.tools" ||
    relPath.last == "MANIFEST.MF" ||
    relPath.last == "LICENSE" ||
    relPath.last == "LICENSE.TXT" ||
    relPath.last == "NOTICE" ||
    relPath.last == "NOTICE.TXT" ||
    relPath.last == "kotlin-project-structure-metadata.json" ||
    relPath.last == "module-info.class"
  }

  private def waitForDevice(adbPath: PathRef, emulator: String, logger: Logger): String = {
    val BootedIndicator = "1"
    def getBootflag: String = {
      val result = os.call(
        (
          adbPath.path,
          "-s",
          emulator,
          "shell",
          "getprop",
          "sys.boot_completed"
        ),
        check = false
      )
      if (result.exitCode != 0)
        "0"
      else
        result.out.trim()
    }

    var bootflag = getBootflag
    var triesLeft = 25

    while (bootflag != BootedIndicator && triesLeft > 0) {
      logger.debug(s"Waiting for device to boot. Bootflag: $bootflag . Tries left ${triesLeft}")
      Thread.sleep(1000)
      triesLeft -= 1
      bootflag = getBootflag
    }

    if (bootflag == BootedIndicator)
      emulator
    else
      throw new Exception("Device failed to boot")
  }

  /**
   * Converts the generated JAR file into a DEX file using the `d8`
   * through the [[androidBuildSettings]].
   *
   * @return os.Path to the Generated DEX File Directory
   */
  def androidDex: T[PathRef] = Task {

    val dex = androidD8Dex()

    Task.log.debug("Building dex with command: " + dex.dexCliArgs.mkString(" "))

    os.call(dex.dexCliArgs)

    PathRef(dex.outPath.path)

  }

  // uses the d8 tool to generate the dex file, when minification is disabled
  private def androidD8Dex
      : Task[(outPath: PathRef, dexCliArgs: Seq[String], appCompiledFiles: Seq[PathRef])] = Task {

    val outPath = Task.dest

    val appCompiledPathRefs = androidPackagedCompiledClasses() ++ androidPackagedClassfiles()

    val appCompiledFiles = appCompiledPathRefs.map(_.path.toString())

    val libsJarPathRefs = androidPackagedDeps()
      .filter(_ != androidSdkModule().androidJarPath())

    val libsJarFiles = libsJarPathRefs.map(_.path.toString())

    val proguardFile = Task.dest / "proguard-rules.pro"
    val knownProguardRules = androidUnpackArchives()
      // TODO need also collect rules from other modules,
      // but Android lib module doesn't yet exist
      .flatMap(_.proguardRules)
      .map(p => os.read(p.path))
      .appendedAll(mainDexPlatformRules)
      .appended(os.read(androidLinkedResources().path / "proguard/main-dex-rules.pro"))
      .mkString("\n")
    os.write(proguardFile, knownProguardRules)

    val d8ArgsBuilder = Seq.newBuilder[String]

    d8ArgsBuilder += androidSdkModule().d8Path().path.toString

    if (androidIsDebug()) {
      d8ArgsBuilder += "--debug"
    } else {
      d8ArgsBuilder += "--release"
    }
    // TODO explore --incremental flag for incremental builds
    d8ArgsBuilder ++= Seq(
      "--output",
      outPath.toString(),
      "--lib",
      androidSdkModule().androidJarPath().path.toString(),
      "--min-api",
      androidMinSdk().toString,
      "--main-dex-rules",
      proguardFile.toString()
    ) ++ appCompiledFiles ++ libsJarFiles

    val d8Args = d8ArgsBuilder.result()

    Task.log.info(s"Running d8 with the command: ${d8Args.mkString(" ")}")

    (PathRef(outPath), d8Args, appCompiledPathRefs ++ libsJarPathRefs)
  }

  trait AndroidAppTests extends AndroidAppModule with JavaTests {

    override def androidCompileSdk: T[Int] = outer.androidCompileSdk()
    override def androidMinSdk: T[Int] = outer.androidMinSdk()
    override def androidTargetSdk: T[Int] = outer.androidTargetSdk()
    override def androidSdkModule: ModuleRef[AndroidSdkModule] = outer.androidSdkModule
    override def androidManifest: T[PathRef] = outer.androidManifest()

    override def androidApplicationId: String = s"${outer.androidApplicationId}.test"

    override def androidApplicationNamespace: String = s"${outer.androidApplicationNamespace}.test"

    override def moduleDir: Path = outer.moduleDir

    override def sources: T[Seq[PathRef]] = Task.Sources("src/test/java")
    def androidResources: T[Seq[PathRef]] = Task.Sources()

    override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
      baseDirectory = Some((moduleDir / "src/test").toNIO),
      canTest = true
    )

  }

  trait AndroidAppInstrumentedTests extends AndroidAppModule with AndroidTestModule {
    override def moduleDir = outer.moduleDir

    override def moduleDeps: Seq[JavaModule] = Seq(outer)

    override def androidCompileSdk: T[Int] = outer.androidCompileSdk()
    override def androidMinSdk: T[Int] = outer.androidMinSdk()
    override def androidTargetSdk: T[Int] = outer.androidTargetSdk()

    override def androidIsDebug: T[Boolean] = Task { true }

    override def resolutionParams: Task[ResolutionParams] = Task.Anon(outer.resolutionParams())

    override def androidApplicationId: String = s"${outer.androidApplicationId}.test"
    override def androidApplicationNamespace: String = s"${outer.androidApplicationNamespace}.test"

    override def androidReleaseKeyAlias: T[Option[String]] = outer.androidReleaseKeyAlias()
    override def androidReleaseKeyName: Option[String] = outer.androidReleaseKeyName
    override def androidReleaseKeyPass: T[Option[String]] = outer.androidReleaseKeyPass()
    override def androidReleaseKeyStorePass: T[Option[String]] = outer.androidReleaseKeyStorePass()
    override def androidReleaseKeyPath: T[Seq[PathRef]] = outer.androidReleaseKeyPath()

    override def androidEmulatorPort: String = outer.androidEmulatorPort

    override def sources: T[Seq[PathRef]] = Task.Sources("src/androidTest/java")

    def androidResources: T[Seq[PathRef]] = Task.Sources("src/androidTest/res")

    private def androidInstrumentedTestsBaseManifest: Task[Elem] = Task.Anon {
      val label = s"Tests for ${outer.androidApplicationId}"
      val instrumentationName = testFramework()

      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package={
        androidApplicationId
      }>

        <application>
          <uses-library android:name="android.test.runner"/>
        </application>

        <instrumentation android:name= {instrumentationName}
                         android:handleProfiling="false"
                         android:functionalTest="false"
                         android:label={label}/>
      </manifest>
    }

    /**
     * The android manifest of the instrumented tests
     * has a different package from the app to differentiate installations
     * @return
     */
    override def androidManifest: T[PathRef] = Task {
      val destManifest = Task.dest / "AndroidManifest.xml"
      os.write(destManifest, androidInstrumentedTestsBaseManifest().toString)
      PathRef(destManifest)
    }

    private def androidxTestManifests: Task[Seq[PathRef]] = Task {
      androidUnpackArchives().flatMap {
        unpackedArchive =>
          unpackedArchive.manifest.map(_.path)
      }.filter {
        case manifest: os.Path =>
          val manifestXML = XML.loadFile(manifest.toString)
          (manifestXML \\ "manifest")
            .map(_ \ "@package").map(_.text).exists(_.startsWith("androidx.test"))
      }.map(PathRef(_))
    }

    override def androidMergeableManifests: Task[Seq[PathRef]] = Task {
      Seq(outer.androidDebugManifestLocation()).filter(pr =>
        os.exists(pr.path)
      ) ++ androidxTestManifests()
    }

    /**
     * Args for the manifest merger, to create a merged manifest for instrumented tests via [[androidMergedManifest]].
     *
     * See [[https://developer.android.com/build/manage-manifests]] for more details.
     */
    override def androidMergedManifestArgs: Task[Seq[String]] = Task.Anon {
      Seq(
        "--main",
        androidManifest().path.toString(),
        "--remove-tools-declarations",
        "--property",
        s"min_sdk_version=${androidMinSdk()}",
        "--property",
        s"target_sdk_version=${androidTargetSdk()}",
        "--property",
        s"version_code=${androidVersionCode()}",
        "--property",
        s"target_package=${outer.androidApplicationId}",
        "--property",
        s"version_name=${androidVersionName()}"
      ) ++ androidMergeableManifests().flatMap(m => Seq("--libs", m.path.toString))
    }

    override def androidVirtualDeviceIdentifier: String = outer.androidVirtualDeviceIdentifier
    override def androidEmulatorArchitecture: String = outer.androidEmulatorArchitecture

    def testFramework: T[String]

    /**
     * Re/Installs the app apk and then the test apk on the [[runningEmulator]]
     * @return
     */
    def androidTestInstall(): Command[String] = Task.Command {

      val emulator = outer.androidInstallTask()

      os.call(
        (
          androidSdkModule().adbPath().path,
          "-s",
          emulator,
          "install",
          "-t",
          androidTestApk().path
        )
      )
      emulator
    }

    /**
     * Runs the tests on the [[runningEmulator]] with the [[androidTestApk]]
     * against the [[androidApk]]
     * @param args
     * @param globSelectors
     * @return
     */
    override def testTask(
        args: Task[Seq[String]],
        globSelectors: Task[Seq[String]]
    ): Task[(msg: String, results: Seq[TestResult])] = Task.Anon {
      val device = androidTestInstall().apply()

      val instrumentOutput = os.proc(
        (
          androidSdkModule().adbPath().path,
          "-s",
          device,
          "shell",
          "am",
          "instrument",
          "-w",
          "-r",
          s"${androidApplicationId}/${testFramework()}"
        )
      ).spawn()

      val outputReader = instrumentOutput.stdout.buffered

      val (doneMsg, results) = InstrumentationOutput.parseTestOutputStream(outputReader)(Task.log)
      val res = TestModule.handleResults(doneMsg, results, Task.ctx(), testReportXml())

      res

    }

    /**
     * The androidTestClasspath dictates what we are going to package
     * in the test apk. This should have all moduleDeps except the main AndroidAppModule
     * as its apk is installed separately
     */
    def androidTransitiveTestClasspath: T[Seq[PathRef]] = Task {
      Task.traverse(transitiveModuleCompileModuleDeps) {
        m =>
          Task.Anon(m.localRunClasspath())
      }().flatten
    }

    /** The instrumented dex should just contain the test dependencies and locally tested files */
    override def androidPackagedClassfiles: T[Seq[PathRef]] = Task {
      (testClasspath() ++ androidTransitiveTestClasspath())
        .map(_.path).filter(os.isDir)
        .flatMap(os.walk(_))
        .filter(os.isFile)
        .filter(_.ext == "class")
        .map(PathRef(_))
    }

    override def androidPackagedDeps: T[Seq[PathRef]] = Task {
      androidResolvedMvnDeps()
    }

    /**
     * The instrumented tests are packaged with testClasspath which already contains the
     * user compiled classes
     */
    override def androidPackagedCompiledClasses: T[Seq[PathRef]] = Task {
      Seq.empty[PathRef]
    }

    /** Builds the apk including the integration tests (e.g. from androidTest) */
    def androidTestApk: T[PathRef] = androidApk()

    @internal
    override def bspBuildTarget: BspBuildTarget = super[AndroidTestModule].bspBuildTarget.copy(
      baseDirectory = Some((moduleDir / "src/androidTest").toNIO),
      canRun = false
    )

  }

}

// TODO this maybe not needed at all, maybe it is better just to return the list of the root folders and then reference
//  each item type manually.
/**
 * Descriptor of unpacked `.aar` dependency structure.
 * @param name dependency name
 * @param classesJar path to the classes.jar
 * @param proguardRules path to the proguard rules
 * @param androidResources path to the res folder
 * @param manifest path to the AndroidManifest.xml
 * @param lintJar path to the lint.jar file
 * @param metaInf path to the META-INF folder
 * @param nativeLibs path to the native .so libraries root folder
 * @param baselineProfile path to the baseline profile file
 * @param rTxtFile path to the R.txt
 * @param publicResFile path to public.txt file
 */
case class UnpackedDep(
    name: String,
    classesJar: Option[PathRef],
    repackagedJars: Seq[PathRef],
    proguardRules: Option[PathRef],
    androidResources: Option[PathRef],
    manifest: Option[PathRef],
    lintJar: Option[PathRef],
    metaInf: Option[PathRef],
    nativeLibs: Option[PathRef],
    baselineProfile: Option[PathRef],
    rTxtFile: Option[PathRef],
    publicResFile: Option[PathRef]
)

object UnpackedDep {
  implicit val rw: ReadWriter[UnpackedDep] = macroRW
}
