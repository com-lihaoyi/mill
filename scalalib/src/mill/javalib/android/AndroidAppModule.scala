package mill.javalib.android

import mill.*
import mill.api.PathRef
import mill.define.ModuleRef
import mill.scalalib.*
import os.{CommandResult, Path}


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

  final def root: Path = super.millSourcePath
  private def src: Path = root / "src"
  override def millSourcePath: Path = src / "main"

  override def sources: T[Seq[PathRef]] = Task.Sources(millSourcePath)

  private def bannedModules(classpath: PathRef): Boolean =
    !classpath.path.last.contains("-jvm")
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

  /** The location of the keystore */
  def releaseKeyPath: Path
  /**
   * Default path to the keystore file, derived from `androidReleaseKeyName()`.
   * Users can customize the keystore file name to change this path.
   */
  def androidReleaseKeyPath: T[PathRef] = Task.Source(releaseKeyPath / androidReleaseKeyName())

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

  def res: T[Seq[PathRef]] = Task.Sources { millSourcePath / "res" }
  /**
   * Combines module resources with those unpacked from AARs.
   */
  def resources: T[Seq[PathRef]] = Task {
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
        jarFile.toString,
        "--lib",
        androidSdkModule().androidJarPath().path.toString()
      ) ++ os.walk(compile().classes.path).filter(p => p.ext == "class").map(
        _.toString
      ) ++ convertJarDepsToDex().map(_.path.toString())
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

  /** Gets all the jars from the classpath and creates an apk with them
   * to be bundled with the app's code apk */
  def convertJarDepsToDex: T[Seq[PathRef]] = Task {
    val androidJar = androidSdkModule().androidJarPath()
    // TODO hack until android classpath resolution + dependencies is
    // properly fixed
    val dependenciesClasspath = compileClasspath().filterNot(
      pr =>
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

  /** Generates a debug apk
   * TODO: this needs to work as the android debug apk functionality,
   * which uses a debug keystore in $HOME/.android/debug.keystore .
   * For now this method is a placeholder and uses androidApk to make
   * integration tests work
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
   * Installs the app to the available android device.
   *
   * @return
   */
  def install: Target[CommandResult] = Task {
    os.call(
      (androidSdkModule().adbPath().path, "install", "-r", androidApk().path)
    )
  }

  trait AndroidAppTests extends JavaTests {
    def testPath: T[Seq[PathRef]] = Task.Sources(src / "test")

    override def allSources: T[Seq[PathRef]] = Task {
      super.allSources() ++ testPath()
    }
  }

  trait AndroidAppIntegrationTests extends AndroidAppModule with AndroidTestModule {
    override def millSourcePath: Path = src / "main"

    def androidTestPath: Path = src / "androidTest"

    override def sources: T[Seq[PathRef]] = Task.Sources(millSourcePath, androidTestPath)

    def instrumentationPackage: String

    def testFramework: T[String]

    override def install: Target[CommandResult] = Task {
      os.call(
        (androidSdkModule().adbPath().path, "install", "-r", androidInstantApk().path)
      )
    }

    def test: T[Vector[String]] = Task {
      install()
      val instrumentOutput = os.call(
        (androidSdkModule().adbPath().path, "shell", "am", "instrument", "-w", "-m", s"${instrumentationPackage}/${testFramework()}")
      )

      instrumentOutput.out.lines()
    }


    /** Builds the apk including the integration tests (e.g. from androidTest) */
    def androidInstantApk: T[PathRef] = androidDebugApk
  }
}
