package mill.javalib.android

import mill._
import mill.scalalib._
import mill.api.PathRef
import mill.define.ModuleRef

import upickle.default._

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

  /**
   * Provides access to the Android SDK configuration.
   */
  def androidSdkModule: ModuleRef[AndroidSdkModule]

  /**
   * Provides Path to an XML file containing configuration and metadata about your android application.
   */
  def androidManifest: Task[PathRef] = Task.Source(millSourcePath / "AndroidManifest.xml")

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

  /**
   * Default path to the keystore file, derived from `androidReleaseKeyName()`.
   * Users can customize the keystore file name to change this path.
   */
  def androidReleaseKeyPath: T[PathRef] = Task.Source(millSourcePath / androidReleaseKeyName())

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

  /**
   * Combines module resources with those unpacked from AARs.
   */
  def resources: T[Seq[PathRef]] = Task {
    val (_, resFolders) = androidUnpackArchives()
    super.resources() ++ resFolders
  }

  /**
   * Replaces AAR files in classpath with their extracted JARs.
   */
  override def compileClasspath: T[Agg[PathRef]] = Task {
    val (jarFiles, _) = androidUnpackArchives()
    super.compileClasspath().filter(_.path.ext == "jar") ++ jarFiles
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
      val zipDir = if (segmentsSeq.last == "resources") compiledResDir else compiledLibsResDir
      val zipName = segmentsSeq.takeRight(2).mkString("-") + ".zip"
      val zipPath = zipDir / zipName

      if (zipDir == compiledResDir) resourceZip += zipPath else libZips += zipPath
      os.call((androidSdkModule().aapt2Path().path, "compile", "--dir", resDir, "-o", zipPath))
    }

    val compiledLibsArgs = libZips.map(zip => Seq("-R", zip.toString)).flatten
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
  def unmanagedClasspath: T[Agg[PathRef]] = Task {
    Agg(androidSdkModule().androidJarPath())
  }

  /**
   * Combines standard Java source directories with additional sources generated by
   * the Android resource generation step.
   */
  def generatedSources: T[Seq[PathRef]] = Task {
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
        jarFile.toString
      ) ++ os.walk(compile().classes.path).filter(_.ext == "class").map(
        _.toString
      )
    )

    PathRef(jarFile)
  }

  /**
   * Converts the generated JAR file into a DEX file using the `d8` tool.
   *
   * @return Path to the Generated DEX File Directory
   */
  def androidDex: T[PathRef] = Task {

    os.call((
      androidSdkModule().d8Path().path,
      "--output",
      Task.dest,
      androidJar().path,
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
   * Uses the release keystore path if available; otherwise, defaults to a standard keystore path.
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
  def androidLintRun() = Task.Command {

    val formats = androidLintReportFormat()

    // Generate the alternating flag and file path strings
    val reportArg: Seq[String] = formats.toSeq.flatMap { format =>
      Seq(format.flag, (Task.dest / s"report.${format.extension}").toString)
    }

    // Set path to generated `.jar` files and/or `.class` files
    val cp = runClasspath().map(_.path).filter(os.exists).mkString(":")

    // Set path to the location of the project source codes
    val src = sources().map(_.path).filter(os.exists).mkString(":")

    // Set path to the location of the project ressource codes
    val res = resources().map(_.path).filter(os.exists).mkString(":")

    // Prepare the lint configuration argument if the config path is set
    val configArg = androidLintConfigPath().map(config =>
      Seq("--config", config.path.toString)
    ).getOrElse(Seq.empty)

    // Prepare the lint baseline argument if the baseline path is set
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

}
