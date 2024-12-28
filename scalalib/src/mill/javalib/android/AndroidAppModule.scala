package mill.javalib.android

import coursier.{MavenRepository, Repository}
import mill._
import mill.scalalib._
import mill.api.PathRef
import mill.define.ModuleRef
import upickle.default._

import scala.jdk.OptionConverters.RichOptional

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
trait AndroidAppModule extends JavaModule {

  override def sources: T[Seq[PathRef]] = Task.Sources(millSourcePath / "src/main/java")

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() ++
      Seq(MavenRepository("https://maven.google.com"))
  }

  /* TODO this is a temporary hack to exclude any jvm only tagged dependencies
   * which may conflict with android dependencies. For example, the
   * kotlin coroutines which are provided by both kotlin-core and
   * kotlin-core-jvm . See also https://github.com/com-lihaoyi/mill/issues/3867
   */
  private def bannedModules(classpath: PathRef): Boolean =
    !classpath.path.last.contains("-jvm")

  /**
   * Provides access to the Android SDK configuration.
   */
  def androidSdkModule: ModuleRef[AndroidSdkModule]

  /**
   * Provides os.Path to an XML file containing configuration and metadata about your android application.
   */
  def androidManifest: Task[PathRef] = Task.Source(millSourcePath / "src/main/AndroidManifest.xml")

  /**
   * Adds "aar" to the handled artifact types.
   */
  def artifactTypes: T[Set[coursier.Type]] = Task { super.artifactTypes() + coursier.Type("aar") }

  /**
   * Default name of the keystore file ("keyStore.jks"). Users can customize this value.
   */
  def androidReleaseKeyName: T[String] = Task { "keyStore.jks" }

  /**
   * Default alias in the keystore ("androidKey"). Users can customize this value.
   */
  def androidReleaseKeyAlias: T[String] = Task { "androidKey" }

  /**
   * Default password for the key ("android"). Users can customize this value.
   */
  def androidReleaseKeyPass: T[String] = Task { "android" }

  /**
   * Default password for the keystore ("android"). Users can customize this value.
   */
  def androidReleaseKeyStorePass: T[String] = Task { "android" }

  /** The location of the keystore */
  def releaseKeyPath: os.Path

  /**
   * Default os.Path to the keystore file, derived from `androidReleaseKeyName()`.
   * Users can customize the keystore file name to change this path.
   */
  def androidReleaseKeyPath: T[PathRef] = Task.Source(releaseKeyPath / androidReleaseKeyName())

  /**
   * Specifies the file format(s) of the lint report. Available file formats are defined in AndroidLintReportFormat,
   * such as AndroidLintReportFormat.Html, AndroidLintReportFormat.Xml, AndroidLintReportFormat.Txt, and AndroidLintReportFormat.Sarif.
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
   * Extracts JAR files and resources from AAR dependencies.
   *
   * @return Paths to extracted JAR files and resource folders.
   */
  def androidUnpackArchives: T[(Seq[PathRef], Seq[PathRef])] = Task {
    val aarFiles = super.compileClasspath().map(_.path).filter(_.ext == "aar").toSeq
    var jarFiles: Seq[PathRef] = Seq()
    var resFolders: Seq[PathRef] = Seq()

    for (aarFile <- aarFiles) {
      val extractDir = Task.dest / aarFile.baseName
      os.unzip(aarFile, extractDir)
      jarFiles ++= os.walk(extractDir).filter(_.ext == "jar").map(PathRef(_))
      val resFolder = extractDir / "res"
      if (os.exists(resFolder)) {
        resFolders ++= Seq(PathRef(resFolder))
      }
    }

    (jarFiles, resFolders)
  }

  def res: T[Seq[PathRef]] = Task.Sources { millSourcePath / "src/main/res" }

  /**
   * Combines module resources with those unpacked from AARs.
   */
  override def resources: T[Seq[PathRef]] = Task {
    val (_, resFolders) = androidUnpackArchives()
    res() ++ resFolders
  }

  /**
   * Replaces AAR files in classpath with their extracted JARs.
   */
  override def compileClasspath: T[Agg[PathRef]] = Task {
    val (jarFiles, _) = androidUnpackArchives()
    val jarFilesAgg = super.compileClasspath().filter(_.path.ext == "jar")

    (jarFilesAgg ++ jarFiles).filter(bannedModules)
  }

  /**
   * Specifies AAPT options for Android resource compilation.
   */
  def androidAaptOptions: T[Seq[String]] = Task { Seq("--auto-add-overlay") }

  /**
   * Compiles Android resources and generates `R.java` and `res.apk`.
   *
   * @return PathRef to the directory with `R.java` and `res.apk`.
   *
   * For more details on the aapt2 tool, refer to:
   * [[https://developer.android.com/tools/aapt2 aapt Documentation]]
   */
  def androidResources: T[PathRef] = Task {
    val destPath = Task.dest
    val compiledResDir = destPath / "compiled"
    val compiledLibsResDir = compiledResDir / "libs"
    val resourceZip = collection.mutable.Buffer[os.Path]()
    val libZips = collection.mutable.Buffer[os.Path]()
    os.makeDir.all(compiledLibsResDir)

    for (resDir <- resources().map(_.path).filter(os.exists)) {
      val segmentsSeq = resDir.segments.toSeq
      val zipDir = if (segmentsSeq.last == "res") compiledResDir else compiledLibsResDir

      val zipName = segmentsSeq.takeRight(2).mkString("-") + ".zip"
      val zipPath = zipDir / zipName

      if (zipDir == compiledResDir) resourceZip += zipPath else libZips += zipPath
      os.call((androidSdkModule().aapt2Path().path, "compile", "--dir", resDir, "-o", zipPath))
    }

    val compiledLibsArgs = libZips.flatMap(zip => Seq("-R", zip.toString))

    val resourceZipArg = resourceZip.headOption.map(_.toString).getOrElse("")

    os.call(
      Seq(
        androidSdkModule().aapt2Path().path.toString,
        "link",
        "-I",
        androidSdkModule().androidJarPath().path.toString,
        "--manifest",
        androidManifest().path.toString,
        "--java",
        destPath.toString,
        "-o",
        (destPath / "res.apk").toString
      ) ++ androidAaptOptions() ++ compiledLibsArgs ++ Seq(resourceZipArg)
    )

    PathRef(destPath)
  }

  /**
   * Adds the Android SDK JAR file to the classpath during the compilation process.
   */
  override def unmanagedClasspath: T[Agg[PathRef]] = Task {
    Agg(androidSdkModule().androidJarPath())
  }

  /**
   * Combines standard Java source directories with additional sources generated by
   * the Android resource generation step.
   */
  override def generatedSources: T[Seq[PathRef]] = Task {
    super.generatedSources() ++ Seq(androidResources())
  }

  /**
   * Packages the compiled Java `.class` files into a JAR file using the D8 tool.
   *
   * The D8 compiler is used here to package and optimize the Java bytecode into a format
   * suitable for Android (DEX). D8 converts the Java `.class` files into a jar file which is
   * suitable for DEX (Dalvik Executable) format and is required for Android runtime.
   *
   * For more details on the d8 tool, refer to:
   * [[https://developer.android.com/tools/d8 d8 Documentation]]
   */
  def androidJar: T[PathRef] = Task {
    val jarFile: os.Path = Task.dest / "app.jar"

    os.call(
      Seq(
        androidSdkModule().d8Path().path.toString,
        "--output",
        jarFile.toString,
        "--lib",
        androidSdkModule().androidJarPath().path.toString()
      ) ++ os.walk(compile().classes.path).filter(p => p.ext == "class").map(
        _.toString
      ) ++ androidRuntimeDependencies().map(_.path.toString())
    )

    PathRef(jarFile)
  }

  /**
   * Converts the generated JAR file into a DEX file using the `d8` tool.
   *
   * @return os.Path to the Generated DEX File Directory
   */
  def androidDex: T[PathRef] = Task {

    os.call((
      androidSdkModule().d8Path().path,
      "--output",
      Task.dest,
      androidJar().path,
      "--lib",
      androidSdkModule().androidJarPath().path
    ))

    PathRef(Task.dest)
  }

  /**
   * Packages DEX files and Android resources into an unsigned APK.
   *
   * @return A `PathRef` to the generated unsigned APK file (`app.unsigned.apk`).
   */
  def androidUnsignedApk: T[PathRef] = Task {
    val unsignedApk: os.Path = Task.dest / "app.unsigned.apk"

    os.copy(androidResources().path / "res.apk", unsignedApk)
    os.zip(unsignedApk, Seq(androidDex().path / "classes.dex"))

    PathRef(unsignedApk)
  }

  /**
   * Gets all the jars from the classpath to be passed to d8 for packaging
   * the app with them.
   */
  def androidRuntimeDependencies: T[Seq[PathRef]] = Task {
    val androidJar = androidSdkModule().androidJarPath()
    // TODO hack until android classpath resolution + dependencies is
    // properly fixed
    val dependenciesClasspath = compileClasspath().filterNot(pr =>
      pr.path == androidJar.path
    )
    val compiledClassPathJars: Seq[PathRef] = dependenciesClasspath.toSeq

    compiledClassPathJars
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
   * Generates the command-line arguments required for Android app signing.
   *
   * Uses the release keystore os.Path if available; otherwise, defaults to a standard keystore path.
   * Includes arguments for the keystore path, key alias, and passwords.
   *
   * @return A `Task` producing a sequence of strings for signing configuration.
   */
  def androidSignKeyDetails: T[Seq[String]] = Task {
    val keyPath = {
      if (!os.exists(androidReleaseKeyPath().path)) { androidKeystore().path }
      else { androidReleaseKeyPath().path }
    }

    Seq(
      "--ks",
      keyPath.toString,
      "--ks-key-alias",
      androidReleaseKeyAlias(),
      "--ks-pass",
      s"pass:${androidReleaseKeyStorePass()}",
      "--key-pass",
      s"pass:${androidReleaseKeyPass()}"
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
    val signedApk: os.Path = Task.dest / "app.apk"

    os.call(
      Seq(
        androidSdkModule().apksignerPath().path.toString,
        "sign",
        "--in",
        androidAlignedUnsignedApk().path.toString,
        "--out",
        signedApk.toString
      ) ++ androidSignKeyDetails()
    )
    PathRef(signedApk)
  }

  /* TODO: this needs to work as the android debug apk functionality,
   * which uses a debug keystore in $HOME/.android/debug.keystore .
   * For now this method is a placeholder and uses androidApk to make
   * integration tests work
   */
  /**
   * Generates a debug apk
   */
  def androidDebugApk: T[PathRef] = androidApk

  /**
   * Generates a new keystore file if it does not exist.
   *
   * A keystore is required to sign the APK for distribution. This method checks if a keystore
   * exists, and if not, generates a new one using the `keytool` utility. The keystore holds
   * the cryptographic keys used to sign the APK.
   *
   * For more details on the keytool utility, refer to:
   * [[https://docs.oracle.com/javase/8/docs/technotes/tools/windows/keytool.html keytool Documentation]]
   */
  def androidKeystore: T[PathRef] = Task(persistent = true) {
    val keystoreFile: os.Path = androidReleaseKeyPath().path

    if (!os.exists(keystoreFile)) {
      os.call((
        "keytool",
        "-genkeypair",
        "-keystore",
        keystoreFile,
        "-alias",
        "androidkey",
        "-dname",
        "CN=MILL, OU=MILL, O=MILL, L=MILL, S=MILL, C=MILL",
        "-validity",
        "10000",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-storepass",
        "android",
        "-keypass",
        "android"
      ))
    }

    PathRef(keystoreFile)
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
        millSourcePath.toString,
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
  def sdkInstallSystemImage: Target[String] = Task {
    val image =
      s"system-images;${androidSdkModule().platformsVersion()};google_apis_playstore;${androidEmulatorArchitecture}"
    os.call((
      androidSdkModule().sdkManagerPath().path,
      "--install",
      image
    ))
    image
  }

  /**
   * Creates the android virtual device identified in virtualDeviceIdentifier
   */
  def createAndroidVirtualDevice: T[os.CommandResult] = Task {
    os.call((
      androidSdkModule().avdPath().path,
      "create",
      "avd",
      "--name",
      androidVirtualDeviceIdentifier,
      "--package",
      sdkInstallSystemImage(),
      "--device",
      androidDeviceId,
      "--force"
    ))
  }

  /**
   * Deletes  the android device
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
  def startAndroidEmulator: T[Option[String]] = Task {
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

    val bootMessage = startEmuCmd.stdout.buffered.lines().filter(l => {
      Task.log.debug(l.trim())
      l.contains("Boot completed in")
    }).findFirst().toScala

    if (bootMessage.isEmpty) {
      throw new Exception(s"Emulator failed to start: ${startEmuCmd.exitCode()}")
    }

    Task.log.info(s"Emulator started with message ${bootMessage}")

    bootMessage
  }

  def waitForDevice = Task {
    val emulator = runningEmulator()
    os.call((
      androidSdkModule().adbPath(),
      "-s",
      emulator,
      "wait-for-device"
    ))
    val bootflag = os.call(
      (
        androidSdkModule().adbPath(),
        "-s",
        emulator,
        "shell",
        "getprop",
        "sys.boot_completed"
      )
    )

    Task.log.info(s"${emulator}, bootflag is ${bootflag}")

    emulator
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
    s"emulator-${androidEmulatorPort}"
  }

  /**
   * Installs the app to the android device identified by this configuration in [[androidVirtualDeviceIdentifier]].
   *
   * @return The name of the device the app was installed to
   */
  def androidInstall: Target[String] = Task {
    val emulator = runningEmulator()

    os.call(
      (androidSdkModule().adbPath().path, "-s", emulator, "install", "-r", androidApk().path)
    )

    emulator
  }

  private val parent: AndroidAppModule = this

  trait AndroidAppTests extends JavaTests {
    private def testPath = parent.millSourcePath / "src/test"

    override def sources: T[Seq[PathRef]] = parent.sources() :+ PathRef(testPath / "java")

    override def resources: T[Seq[PathRef]] = parent.res() :+ PathRef(testPath / "res")
  }

  trait AndroidAppIntegrationTests extends AndroidAppModule with AndroidTestModule {
    private def androidMainSourcePath = parent.millSourcePath
    private def androidTestPath = androidMainSourcePath / "src/androidTest"

    override def androidEmulatorPort: String = parent.androidEmulatorPort

    override def sources: T[Seq[PathRef]] = parent.sources() :+ PathRef(androidTestPath / "java")

    /** The resources in res directories of both main source and androidTest sources */
    override def res: T[Seq[PathRef]] =
      parent.res() :+ PathRef(androidTestPath / "res")

    /* TODO on debug work, an AndroidManifest.xml with debug and instrumentation settings
     * will need to be created. Then this needs to point to the location of that debug
     * AndroidManifest.xml
     */
    override def androidManifest: Task[PathRef] =
      Task.Source(androidMainSourcePath / "src/main/AndroidManifest.xml")

    override def androidVirtualDeviceIdentifier: String = parent.androidVirtualDeviceIdentifier
    override def androidEmulatorArchitecture: String = parent.androidEmulatorArchitecture

    def instrumentationPackage: String

    def testFramework: T[String]

    override def androidInstall: Target[String] = Task {
      val emulator = runningEmulator()
      os.call(
        (
          androidSdkModule().adbPath().path,
          "-s",
          emulator,
          "install",
          "-r",
          androidInstantApk().path
        )
      )
      emulator
    }

    def test: T[Vector[String]] = Task {
      val device = androidInstall()

      val instrumentOutput = os.call(
        (
          androidSdkModule().adbPath().path,
          "-s",
          device,
          "shell",
          "am",
          "instrument",
          "-w",
          "-m",
          s"${instrumentationPackage}/${testFramework()}"
        )
      )
      instrumentOutput.out.lines()
    }

    /** Builds the apk including the integration tests (e.g. from androidTest) */
    def androidInstantApk: T[PathRef] = androidDebugApk
  }
}
