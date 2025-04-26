package mill.androidlib

import mill.*
import mill.api.Logger
import mill.api.internal.{BspBuildTarget, EvaluatorApi, internal}
import mill.define.{ModuleRef, PathRef, Task}
import mill.scalalib.*
import mill.testrunner.TestResult
import mill.util.Jvm
import os.RelPath
import upickle.default.*

import scala.jdk.OptionConverters.RichOptional
import scala.xml.{Attribute, Elem, NodeBuffer, Null, Text, XML}

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
   * The namespace of the android application which is used
   * to specify the fully qualified classpath of the activity.
   *
   * For instance, it is used as the package name in Android Manifest
   */
  def androidApplicationNamespace: String

  /**
   * In the case of android apps this the [[androidApplicationNamespace]].
   * @return
   */
  protected override def androidGeneratedResourcesPackage: String = androidApplicationNamespace

  /**
   * Android Application Id which is typically package.main .
   * Can be used for build variants.
   *
   * Build variant feature is not yet implemented!
   */
  def androidApplicationId: String

  private def androidManifestUsesSdkSection: Task[Elem] = Task.Anon {
    val minSdkVersion = androidMinSdk().toString
    val targetSdkVersion = androidTargetSdk().toString
    <uses-sdk android:minSdkVersion={minSdkVersion} android:targetSdkVersion={targetSdkVersion}/>
  }

  /**
   * Provides os.Path to an XML file containing configuration and metadata about your android application.
   * TODO dynamically add android:debuggable
   */
  override def androidManifest: T[PathRef] = Task {
    val manifestFromSourcePath = moduleDir / "src/main/AndroidManifest.xml"

    val manifestElem = XML.loadFile(manifestFromSourcePath.toString())
    // add the application package
    val manifestWithPackage =
      manifestElem % Attribute(None, "package", Text(androidApplicationId), Null)

    val manifestWithUsesSdk = manifestWithPackage.copy(
      child = androidManifestUsesSdkSection() ++ manifestWithPackage.child
    )

    val generatedManifestPath = Task.dest / "AndroidManifest.xml"
    os.write(generatedManifestPath, manifestWithUsesSdk.mkString)

    PathRef(generatedManifestPath)
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

  /**
   * Combines module resources with those unpacked from AARs.
   */
  override def resources: T[Seq[PathRef]] = Task {
    val libResFolders = androidUnpackArchives().flatMap(_.resources)
    libResFolders :+ PathRef(moduleDir / "src/main/res")
  }

  @internal
  override def bspCompileClasspath = Task.Anon { (ev: EvaluatorApi) =>
    compileClasspath().map(
      _.path
    ).map(UnresolvedPath.ResolvedPath(_)).map(_.resolve(os.Path(ev.outPathJava))).map(sanitizeUri)
  }

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    baseDirectory = Some((moduleDir / "src/main").toNIO),
    tags = Seq("application")
  )

  def androidTransformAarFiles: T[Seq[UnpackedDep]] = Task {
    val transformDest = Task.dest / "transform"
    val aarFiles = super.resolvedRunMvnDeps()
      .map(_.path)
      .filter(_.ext == "aar")
      .distinct

    // TODO do it in some shared location, otherwise each module is doing the same, having its own copy for nothing
    extractAarFiles(aarFiles, transformDest)
  }

  override def resolvedRunMvnDeps: T[Seq[PathRef]] = Task {
    val transformedAarFilesToJar: Seq[PathRef] = androidTransformAarFiles().flatMap(_.classesJar)
    val jarFiles = super.resolvedRunMvnDeps()
      .filter(_.path.ext == "jar")
      .distinct
    transformedAarFilesToJar ++ jarFiles
  }

  /**
   * Replaces AAR files in classpath with their extracted JARs.
   */
  override def compileClasspath: T[Seq[PathRef]] = Task {
    // TODO process metadata shipped with Android libs. It can have some rules with Target SDK, for example.
    // TODO support baseline profiles shipped with Android libs.

    (super.compileClasspath().filter(_.path.ext != "aar") ++ resolvedRunMvnDeps()).map(
      _.path
    ).distinct.map(PathRef(_))
  }

  def androidTransitiveResources: Target[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleCompileModuleDeps) { m =>
      Task.Anon(m.resources())
    }().flatten
  }

  /**
   * Adds the Android SDK JAR file to the classpath during the compilation process.
   */
  override def unmanagedClasspath: T[Seq[PathRef]] = Task {
    Seq(androidSdkModule().androidJarPath())
  }

  /**
   * Combines standard Java source directories with additional sources generated by
   * the Android resource generation step.
   */
  override def generatedSources: T[Seq[PathRef]] = Task {
    androidLibsRClasses()
  }

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
   * Packages DEX files and Android resources into an unsigned APK.
   *
   * @return A `PathRef` to the generated unsigned APK file (`app.unsigned.apk`).
   */
  def androidUnsignedApk: T[PathRef] = Task {
    val unsignedApk = Task.dest / "app.unsigned.apk"

    os.copy(androidResources()._1.path / "res.apk", unsignedApk)
    val dexFiles = os.walk(androidDex().path)
      .filter(_.ext == "dex")
      .map(os.zip.ZipSource.fromPath)
    // TODO probably need to merge all content, not only in META-INF of classes.jar, but also outside it
    val metaInf = androidLibsClassesJarMetaInf()
      .map(ref => {
        def metaInfRoot(p: os.Path): os.Path = {
          var current = p
          while (!current.endsWith(os.rel / "META-INF")) {
            current = current / os.up
          }
          current / os.up
        }
        val path = ref.path
        os.zip.ZipSource.fromPathTuple((path, path.subRelativeTo(metaInfRoot(path))))
      })
      .distinctBy(_.dest.get)

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
    // TODO pack also native (.so) libraries

    PathRef(unsignedApk)
  }

  /**
   * Creates a merged manifest from application and dependencies manifests.
   *
   * See [[https://developer.android.com/build/manage-manifests]] for more details.
   */
  def androidMergedManifest: T[PathRef] = Task {
    val libManifests = androidUnpackArchives().flatMap(_.manifest)
    val mergedManifestPath = Task.dest / "AndroidManifest.xml"
    // TODO put it to the dedicated worker if cost of classloading is too high
    Jvm.callProcess(
      mainClass = "com.android.manifmerger.Merger",
      mainArgs = Seq(
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
        "--out",
        mergedManifestPath.toString()
      ) ++ libManifests.flatMap(m => Seq("--libs", m.path.toString())),
      classPath = manifestMergerClasspath().map(_.path).toVector,
      stdin = os.Inherit,
      stdout = os.Inherit
    )
    PathRef(mergedManifestPath)
  }

  /**
   * Classpath for the manifest merger run.
   */
  def manifestMergerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        mvn"com.android.tools.build:manifest-merger:${androidSdkModule().manifestMergerVersion()}"
      )
    )
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

  // TODO alias, keystore pass and pass below are sensitive credentials and shouldn't be leaked to disk/console.
  // In the current state they are leaked, because Task dumps output to the json.
  // Should be fixed ASAP.

  /**
   * Name of the key alias in the release keystore. Default is not set.
   */
  def androidReleaseKeyAlias: T[Option[String]] = Task {
    None
  }

  /**
   * Name of the release keystore file. Default is not set.
   */
  def androidReleaseKeyName: T[Option[String]] = Task {
    None
  }

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
   * Generates the command-line arguments required for Android app signing.
   *
   * Uses the release keystore if release build type is set; otherwise, defaults to a generated debug keystore.
   */
  def androidSignKeyDetails: T[Seq[String]] = Task {

    val keystorePass = {
      if (androidIsDebug()) debugKeyStorePass else androidReleaseKeyStorePass().get
    }
    val keyAlias = {
      if (androidIsDebug()) debugKeyAlias else androidReleaseKeyAlias().get
    }
    val keyPass = {
      if (androidIsDebug()) debugKeyPass else androidReleaseKeyPass().get
    }

    Seq(
      "--ks",
      androidKeystore().path.toString,
      "--ks-key-alias",
      keyAlias,
      "--ks-pass",
      s"pass:$keystorePass",
      "--key-pass",
      s"pass:$keyPass"
    )
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

  def adbDevices: T[String] = Task {
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

  def androidInstallTask = Task.Anon {
    val emulator = runningEmulator()

    os.call(
      (androidSdkModule().adbPath().path, "-s", emulator, "install", "-r", androidApk().path)
    )

    emulator
  }

  /**
   * Default os.Path to the keystore file, derived from `androidReleaseKeyName()`.
   * Users can customize the keystore file name to change this path.
   */
  def androidReleaseKeyPath: T[Option[PathRef]] = Task {
    androidReleaseKeyName().map(name => PathRef(moduleDir / name))
  }

  /*
    The debug keystore is stored in `$HOME/.mill-android`. The practical
  purpose of a global keystore is to avoid the user having to uninstall the
  app everytime the task directory is deleted (as the app signatures will not match).
   */
  private def androidDebugKeystore: Task[PathRef] = Task(persistent = true) {
    val debugFileName = "mill-debug.jks"
    val globalDebugFileLocation = os.home / ".mill-android"

    if (!os.exists(globalDebugFileLocation)) {
      os.makeDir(globalDebugFileLocation)
    }

    val debugKeystoreFile = globalDebugFileLocation / debugFileName

    if (!os.exists(debugKeystoreFile)) {
      // TODO test on windows and mac and/or change implementation with java APIs
      os.call((
        "keytool",
        "-genkeypair",
        "-keystore",
        debugKeystoreFile,
        "-alias",
        debugKeyAlias,
        "-dname",
        "CN=MILL, OU=MILL, O=MILL, L=MILL, S=MILL, C=MILL",
        "-validity",
        "10000",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-storepass",
        debugKeyStorePass,
        "-keypass",
        debugKeyPass
      ))
    }

    PathRef(debugKeystoreFile)
  }

  protected def androidKeystore: T[PathRef] = Task {
    val pathRef = if (androidIsDebug()) {
      androidDebugKeystore()
    } else {
      androidReleaseKeyPath().get
    }
    pathRef
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

  def androidModuleGeneratedDexVariants: Task[AndroidModuleGeneratedDexVariants] = Task {
    val androidDebugDex = T.dest / "androidDebugDex.dest"
    os.makeDir(androidDebugDex)
    val androidReleaseDex = T.dest / "androidReleaseDex.dest"
    os.makeDir(androidReleaseDex)
    val mainDexListOutput = T.dest / "main-dex-list-output.txt"

    val proguardFileDebug = androidDebugDex / "proguard-rules.pro"

    val knownProguardRulesDebug = androidUnpackArchives()
      // TODO need also collect rules from other modules,
      // but Android lib module doesn't yet exist
      .flatMap(_.proguardRules)
      .map(p => os.read(p.path))
      .appendedAll(mainDexPlatformRules)
      .appended(os.read(androidResources()._1.path / "main-dex-rules.pro"))
      .mkString("\n")
    os.write(proguardFileDebug, knownProguardRulesDebug)

    val proguardFileRelease = androidReleaseDex / "proguard-rules.pro"

    val knownProguardRulesRelease = androidUnpackArchives()
      // TODO need also collect rules from other modules,
      // but Android lib module doesn't yet exist
      .flatMap(_.proguardRules)
      .map(p => os.read(p.path))
      .appendedAll(mainDexPlatformRules)
      .appended(os.read(androidResources()._1.path / "main-dex-rules.pro"))
      .mkString("\n")
    os.write(proguardFileRelease, knownProguardRulesRelease)

    AndroidModuleGeneratedDexVariants(
      androidDebugDex = PathRef(androidDebugDex),
      androidReleaseDex = PathRef(androidReleaseDex),
      mainDexListOutput = PathRef(mainDexListOutput)
    )
  }

  /**
   * Provides the output path for the generated main-dex list file, which is used
   * during the DEX generation process.
   */
  def mainDexListOutput: T[Option[PathRef]] = Task {
    Some(androidModuleGeneratedDexVariants().mainDexListOutput)
  }

  /** ProGuard/R8 rules configuration files for release target (user-provided and generated) */
  def androidProguardReleaseConfigs: T[Seq[PathRef]] = Task {
    val proguardFilesFromReleaseSettings = androidReleaseSettings().proguardFiles
    val androidProguardPath = androidSdkModule().androidProguardPath().path
    val defaultProguardFile = proguardFilesFromReleaseSettings.defaultProguardFile.map {
      pf => androidProguardPath / pf
    }
    val userProguardFiles = proguardFilesFromReleaseSettings.localFiles

    (defaultProguardFile.toSeq ++ userProguardFiles).map(PathRef(_))
  }

  /**
   * The default release settings with the following settings:
   * - minifyEnabled=true
   * - shrinkEnabled=true
   * - proguardFiles=proguard-android-optimize.txt
   * @return
   */
  def androidReleaseSettings: T[AndroidBuildTypeSettings] = Task {
    AndroidBuildTypeSettings(
      isMinifyEnabled = true,
      isShrinkEnabled = true,
      proguardFiles = ProguardFiles(
        defaultProguardFile = Some("proguard-android-optimize.txt")
      )
    )
  }

  def androidDebugSettings: T[AndroidBuildTypeSettings] = Task {
    AndroidBuildTypeSettings()
  }

  /**
   * Gives the android build type settings for debug or release.
   * Controlled by [[androidIsDebug]] flag!
   * @return
   */
  def androidBuildSettings: T[AndroidBuildTypeSettings] = Task {
    if (androidIsDebug())
      androidDebugSettings()
    else
      androidReleaseSettings()
  }

  /**
   * Converts the generated JAR file into a DEX file using the `d8` or the r8 tool if minification is enabled
   * through the [[androidBuildSettings]].
   *
   * @return os.Path to the Generated DEX File Directory
   */
  def androidDex: T[PathRef] = Task {

    val buildSettings: AndroidBuildTypeSettings = androidBuildSettings()

    val (outPath, dexCliArgs) = {
      if (buildSettings.isMinifyEnabled) {
        androidR8Dex()
      } else
        androidD8Dex()
    }

    Task.log.debug("Building dex with command: " + dexCliArgs.mkString(" "))

    os.call(dexCliArgs)

    outPath

  }

  // uses the d8 tool to generate the dex file, when minification is disabled
  private def androidD8Dex: T[(PathRef, Seq[String])] = Task {

    val outPath = T.dest

    val appCompiledFiles = (androidPackagedCompiledClasses() ++ androidPackagedClassfiles())
      .map(_.path.toString())

    val libsJarFiles = androidPackagedDeps()
      .filter(_ != androidSdkModule().androidJarPath())
      .map(_.path.toString())

    val proguardFile = Task.dest / "proguard-rules.pro"
    val knownProguardRules = androidUnpackArchives()
      // TODO need also collect rules from other modules,
      // but Android lib module doesn't yet exist
      .flatMap(_.proguardRules)
      .map(p => os.read(p.path))
      .appendedAll(mainDexPlatformRules)
      .appended(os.read(androidResources()._1.path / "main-dex-rules.pro"))
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

    PathRef(outPath) -> d8Args
  }

  // uses the R8 tool to generate the dex (to shrink and obfuscate)
  private def androidR8Dex: Task[(PathRef, Seq[String])] = Task {
    val destDir = T.dest / "minify"
    os.makeDir.all(destDir)

    val outputPath = destDir

    T.log.debug("outptuPath: " + outputPath)

    // Define diagnostic output file paths
    val mappingOut = destDir / "mapping.txt"
    val seedsOut = destDir / "seeds.txt"
    val usageOut = destDir / "usage.txt"
    val configOut = destDir / "configuration.txt"
    destDir / "missing_rules.txt"
    val baselineOutOpt = destDir / "baseline-profile-rewritten.txt"
    destDir / "res"

    // Create an extra ProGuard config file that instructs R8 to print seeds and usage.
    val extraRulesFile = destDir / "extra-rules.pro"
    val extraRulesContent =
      s"""-printseeds ${seedsOut.toString}
         |-printusage ${usageOut.toString}
         |""".stripMargin.trim
    os.write.over(extraRulesFile, extraRulesContent)

    val classpathClassFiles: Seq[String] = androidPackagedClassfiles()
      .filter(_.path.ext == "class")
      .map(_.path.toString)

    val appCompiledFiles: Seq[String] = androidPackagedCompiledClasses()
      .filter(_.path.ext == "class")
      .map(_.path.toString)

    val allClassFiles = classpathClassFiles ++ appCompiledFiles

    val r8ArgsBuilder = Seq.newBuilder[String]

    r8ArgsBuilder += androidSdkModule().r8Exe().path.toString

    if (androidIsDebug())
      r8ArgsBuilder += "--debug"
    else
      r8ArgsBuilder += "--release"

    r8ArgsBuilder ++= Seq(
      "--output",
      outputPath.toString,
      "--pg-map-output",
      mappingOut.toString,
      "--pg-conf-output",
      configOut.toString
    )

    if (!androidBuildSettings().enableDesugaring) {
      r8ArgsBuilder += "--no-desugaring"
    }

    if (!androidBuildSettings().isMinifyEnabled) {
      r8ArgsBuilder += "--no-minification"
    }

    if (!androidBuildSettings().isShrinkEnabled) {
      r8ArgsBuilder += "--no-tree-shaking"
    }

    r8ArgsBuilder ++= Seq(
      "--min-api",
      androidMinSdk().toString,
      "--dex"
    )

    // Multi-dex arguments (if any are provided)
    val multiDexArgs =
      mainDexRules().toSeq.flatMap(r => Seq("--main-dex-rules", r.path.toString)) ++
        mainDexList().toSeq.flatMap(l => Seq("--main-dex-list", l.path.toString)) ++
        mainDexListOutput().toSeq.flatMap(l => Seq("--main-dex-list-output", l.path.toString))

    r8ArgsBuilder ++= multiDexArgs

    // Baseline profile rewriting arguments, if a baseline profile is provided.
    val baselineArgs = baselineProfile().map { bp =>
      Seq("--art-profile", bp.path.toString, baselineOutOpt.toString)
    }.getOrElse(Seq.empty)

    r8ArgsBuilder ++= baselineArgs

    // Library arguments: pass each bootclasspath and any additional library classes as --lib.
    val libArgs = libraryClassesPaths().flatMap(ref => Seq("--lib", ref.path.toString))

    r8ArgsBuilder ++= libArgs

    // ProGuard configuration files: add our extra rules file and all provided config files.
    val pgArgs = Seq("--pg-conf", extraRulesFile.toString) ++
      androidProguardReleaseConfigs().flatMap(cfg => Seq("--pg-conf", cfg.path.toString))

    r8ArgsBuilder ++= pgArgs

    r8ArgsBuilder ++= allClassFiles

    val r8Args = r8ArgsBuilder.result()

    PathRef(outputPath) -> r8Args
  }

  trait AndroidAppTests extends AndroidAppModule with JavaTests {

    override def androidCompileSdk: T[Int] = outer.androidCompileSdk()
    override def androidMinSdk: T[Int] = outer.androidMinSdk()
    override def androidTargetSdk: T[Int] = outer.androidTargetSdk()
    override def androidSdkModule: ModuleRef[AndroidSdkModule] = outer.androidSdkModule
    override def androidManifest: T[PathRef] = outer.androidManifest()

    override def androidApplicationId: String = outer.androidApplicationId

    override def androidApplicationNamespace: String = outer.androidApplicationNamespace

    override def moduleDir = outer.moduleDir

    override def sources: T[Seq[PathRef]] = Task.Sources("src/test/java")

    override def resources: T[Seq[PathRef]] = Task.Sources("src/test/res")

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

    override def androidApplicationId: String = s"${outer.androidApplicationId}.test"
    override def androidApplicationNamespace: String = outer.androidApplicationNamespace

    override def androidReleaseKeyAlias: T[Option[String]] = outer.androidReleaseKeyAlias()
    override def androidReleaseKeyName: T[Option[String]] = outer.androidReleaseKeyName()
    override def androidReleaseKeyPass: T[Option[String]] = outer.androidReleaseKeyPass()
    override def androidReleaseKeyStorePass: T[Option[String]] = outer.androidReleaseKeyStorePass()
    override def androidReleaseKeyPath: T[Option[PathRef]] = outer.androidReleaseKeyPath()

    override def androidEmulatorPort: String = outer.androidEmulatorPort

    override def sources: T[Seq[PathRef]] = Task.Sources("src/androidTest/java")

    /** The resources in res directories of both main source and androidTest sources */
    override def resources: T[Seq[PathRef]] = Task {
      val libResFolders = androidUnpackArchives().flatMap(_.resources)
      libResFolders ++ resources0()
    }
    def resources0 = Task.Sources("src/androidTest/res")

    override def generatedSources: T[Seq[PathRef]] = Task.Sources()

    private def androidInstrumentedTestsBaseManifest: Task[Elem] = Task.Anon {
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package={
        androidApplicationId
      }>
        {androidManifestUsesSdkSection()}
      </manifest>
    }

    /**
     * The android manifest of the instrumented tests
     * has a different package from the app to differentiate installations
     * @return
     */
    override def androidManifest: T[PathRef] = Task {
      val baseManifestElem = androidInstrumentedTestsBaseManifest()
      val testFrameworkName = testFramework()
      val manifestWithInstrumentation = {
        val instrumentation =
          <instrumentation android:name={testFrameworkName} android:targetPackage={
            androidApplicationNamespace
          }/>
        baseManifestElem.copy(child = baseManifestElem.child ++ instrumentation)
      }
      val destManifest = Task.dest / "AndroidManifest.xml"
      os.write(destManifest, manifestWithInstrumentation.toString)
      PathRef(destManifest)

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
    ): Task[(String, Seq[TestResult])] = Task.Anon {
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

    /** The instrumented dex should just contain the test dependencies and locally tested files */
    override def androidPackagedClassfiles: T[Seq[PathRef]] = Task {
      testClasspath()
        .map(_.path).filter(os.isDir)
        .flatMap(os.walk(_))
        .filter(os.isFile)
        .filter(_.ext == "class")
        .map(PathRef(_))
    }

    override def androidPackagedDeps: T[Seq[PathRef]] = Task {
      resolvedRunMvnDeps()
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
 * @param resources path to the res folder
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
    proguardRules: Option[PathRef],
    resources: Option[PathRef],
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
