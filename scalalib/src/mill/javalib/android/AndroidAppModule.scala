package mill.javalib.android

import mill._
import mill.scalalib._
import mill.api.PathRef
import mill.define.ModuleRef

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
   * Abstract method to provide access to the Android SDK configuration.
   *
   * This method must be implemented by the concrete class to specify the SDK paths.
   *
   * @return The Android SDK module that is used across the project.
   */
  def androidSdkModule: ModuleRef[AndroidSdkModule]

  /**
   * An XML file containing configuration and metadata about your android application
   */
  def androidManifest: Task[PathRef] = Task.Source(millSourcePath / "AndroidManifest.xml")

  /**
   * Adds the "aar" type to the set of artifact types handled by this module.
   *
   * @return A task that yields an updated set of artifact types including "aar".
   */
  def artifactTypes: T[Set[coursier.Type]] = Task { super.artifactTypes() + coursier.Type("aar") }

  /**
   * Task to extract files from AAR files in the classpath.
   *
   * @return A sequence of `PathRef` pointing to the extracted JAR files.
   */
  def androidUnpackArchives: T[(Seq[PathRef], Seq[PathRef])] = Task {
    // Get all AAR files from the compile classpath
    val aarFiles = super.compileClasspath().map(_.path).filter(_.ext == "aar").toSeq

    // Initialize sequences for jar files and resource folders
    var jarFiles: Seq[PathRef] = Seq()
    var resFolders: Seq[PathRef] = Seq()

    // Process each AAR file using a for loop
    for (aarFile <- aarFiles) {
      val extractDir = Task.dest / aarFile.baseName
      os.unzip(aarFile, extractDir)

      // Collect all .jar files in the AAR directory
      jarFiles ++= os.walk(extractDir).filter(_.ext == "jar").map(PathRef(_))

      // If the res folder exists, add it to the resource folders
      val resFolder = extractDir / "res"
      if (os.exists(resFolder)) {
        resFolders ++= Seq(PathRef(resFolder))
      }
    }

    // Return both jar files and resource folders
    (jarFiles, resFolders)
  }

  /**
   * Overrides the `resources` task to include resources from unpacked AAR files
   *
   * @return Combined sequence of original and filtered AAR resources.
   */
  def resources: T[Seq[PathRef]] = Task {
    // Call the function to unpack AARs and get the jar and resource paths
    val (_, resFolders) = androidUnpackArchives()

    // Combine and return all resources
    super.resources() ++ resFolders
  }

  /**
   * Overrides the compile classpath to replace `.aar` files with the extracted
   * `.jar` files.
   *
   * @return The updated classpath with `.jar` files only.
   */
  override def compileClasspath: T[Agg[PathRef]] = Task {
    // Call the function to get jar files and resource paths
    val (jarFiles, _) = androidUnpackArchives()
    super.compileClasspath().filter(_.path.ext == "jar") ++ jarFiles
  }

  def linkOptions: T[Seq[String]] = Task {
    Seq[String]("--auto-add-overlay")
  }

  /**
   * Compiles and links Android resources using `aapt2`, generating the `R.java` file.
   *
   * @return A `PathRef` to the directory containing the generated `R.java`.
   *
   * For more details on the aapt2 tool, refer to:
   * [[https://developer.android.com/tools/aapt2 aapt Documentation]]
   */
  def androidResources: T[PathRef] = Task {
    val compiledResDir: os.Path = Task.dest / "compiled" // Directory for compiled resources
    val resourceDirs = resources().map(_.path).filter(os.exists) // Merge all resource directories
    os.makeDir.all(compiledResDir)
    val compiledZips = resourceDirs.map { resDir =>
      // Use the last two components of the path as part of the filename
      val lastTwoSegments = resDir.segments.toSeq.takeRight(2).mkString("-")
      val outputZip = compiledResDir / s"$lastTwoSegments.zip"
      os.call((androidSdkModule().aapt2Path().path, "compile", "--dir", resDir, "-o", outputZip))
      outputZip
    }

    // Partition compiledZips into resourceZip and libZips
    // "resources" is used to differ the libs res from app res
    val (resourceZip, libZips) = compiledZips.partition(_.toString.contains("resources"))
    val compiledlibs = libZips.flatMap(zip => Seq("-R", zip.toString))
    println(libZips)
    println(resourceZip)

    os.call(
      Seq(
        androidSdkModule().aapt2Path().path.toString, // AAPT2  tool path
        "link",
        "-I",
        androidSdkModule().androidJarPath().path.toString
      ) ++ linkOptions() ++ compiledlibs ++ Seq(
        "--manifest",
        androidManifest().path.toString, // Use AndroidManifest.xml
        "--java",
        Task.dest.toString, // Generate R.java in the Task.dest
        "-o",
        s"${Task.dest / "res.apk"}",
        resourceZip.headOption.map(_.toString).getOrElse("") // Safe extraction of the first element
      )
    )

    PathRef(Task.dest)
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
   *
   * Ensures that generated files like `R.java` (which contain references to resources)
   * are included in the source set and compiled correctly.
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
        androidSdkModule().d8Path().path.toString, // Call d8 tool
        "--output",
        jarFile.toString, // Output JAR file
        "--no-desugaring" // Disable desugaring
      ) ++ os.walk(compile().classes.path).filter(_.ext == "class").map(
        _.toString
      ) // Get class files
    )

    PathRef(jarFile)
  }

  /**
   * Converts the generated JAR file into a DEX file using the `d8` tool.
   */
  def androidDex: T[PathRef] = Task {

    os.call((
      androidSdkModule().d8Path().path,
      "--output",
      Task.dest,
      androidJar().path, // Use the JAR file from the previous step
      androidSdkModule().androidJarPath().path // Include Android framework classes
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
    // Add the classes.dex file into the unsigned APK as a zip entry
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
      androidSdkModule().zipalignPath().path, // Call zipalign tool
      "-f",
      "-p",
      "4", // Force overwrite, align with 4-byte boundary
      androidUnsignedApk().path, // Use the unsigned APK
      alignedApk // Output aligned APK
    ))

    PathRef(alignedApk)
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

    os.call((
      androidSdkModule().apksignerPath().path,
      "sign", // Call apksigner tool
      "--ks",
      androidKeystore().path, // Path to keystore
      "--ks-key-alias",
      "androidkey", // Key alias
      "--ks-pass",
      "pass:android", // Keystore password
      "--key-pass",
      "pass:android", // Key password
      "--out",
      signedApk, // Output signed APK
      androidAlignedUnsignedApk().path // Use aligned APK
    ))

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
    val keystoreFile: os.Path = Task.dest / "keystore.jks"

    if (!os.exists(keystoreFile)) {
      os.call((
        "keytool",
        "-genkeypair",
        "-keystore",
        keystoreFile, // Generate keystore
        "-alias",
        "androidkey", // Alias for key in the keystore
        "-dname",
        "CN=MILL, OU=MILL, O=MILL, L=MILL, S=MILL, C=IN", // Key details
        "-validity",
        "10000", // Valid for 10,000 days
        "-keyalg",
        "RSA",
        "-keysize",
        "2048", // RSA encryption, 2048-bit key
        "-storepass",
        "android",
        "-keypass",
        "android" // Passwords
      ))
    }

    PathRef(keystoreFile)
  }
}
