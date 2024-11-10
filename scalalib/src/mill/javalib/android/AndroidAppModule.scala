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

    // Process each AAR file
    aarFiles.foreach { aarFile =>
      val extractDir = T.dest / aarFile.baseName
      os.unzip(aarFile, extractDir)

      // Collect all .jar files in the AAR directory
      jarFiles ++= os.walk(extractDir).filter(_.ext == "jar").map(PathRef(_))

      // If the res folder exists, add it to the resource folders
      val resFolder = extractDir / "res"
      if (os.exists(resFolder)) {
        resFolders :+= PathRef(resFolder)
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

  /**
   * Compiles and links Android resources using `aapt2`, generating the `R.java` file.
   *
   * @return A `PathRef` to the directory containing the generated `R.java`.
   *
   * For more details on the aapt2 tool, refer to:
   * [[https://developer.android.com/tools/aapt2 aapt Documentation]]
   */
  def androidResources: T[PathRef] = Task {
    val genDir: os.Path = T.dest // Directory to store generated R.java
    val compiledResDir: os.Path = T.dest / "compiled" // Directory for compiled resources
    val resourceDirs = resources().map(_.path).filter(os.exists) // Merge all resource directories
    os.makeDir.all(compiledResDir)
    var count = 0
    val compiledZips = resourceDirs.map { resDir =>
      val outputZip = compiledResDir / s"${resDir.last}-${count}.zip"
      count = count + 1
      os.call(Seq(
        androidSdkModule().aapt2Path().path.toString, // Call aapt2 tool
        "compile",
        "--dir",
        resDir.toString, // Compile each resource directory
        "-o",
        outputZip.toString // Output directory for compiled resources
      ))
      outputZip
    }

    // Filter to find the single "resources" zip and exclude it from the regular zips
    val ResourceZip = compiledZips.find(_.toString.contains("resources"))
    val libzips = compiledZips.filterNot(_.toString.contains("resources"))

    val compiledlibs = libzips.flatMap(zip => Seq("-R", zip.toString))

    os.call(
      Seq(
        androidSdkModule().aapt2Path().path.toString, // AAPT2  tool path
        "link",
        "-I",
        androidSdkModule().androidJarPath().path.toString, // Include Android SDK JAR
        "--auto-add-overlay" // Automatically add resources from overlays
      ) ++ compiledlibs ++ Seq(
        "--manifest",
        androidManifest().path.toString, // Use AndroidManifest.xml
        "--java",
        genDir.toString, // Generate R.java in the genDir
        "-o",
        s"${genDir / "res.apk"}",
        ResourceZip.map(_.toString).getOrElse("")
      )
    )

    PathRef(genDir)
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
    val jarFile: os.Path = T.dest / "app.jar"

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
    val dexOutputDir: os.Path = T.dest

    os.call(
      Seq(androidSdkModule().d8Path().path.toString, "--output", dexOutputDir.toString) ++
        Seq(
          androidJar().path.toString, // Use the JAR file from the previous step
          androidSdkModule().androidJarPath().path.toString // Include Android framework classes
        )
    )
    PathRef(dexOutputDir)
  }

  /**
   * Packages DEX files and Android resources into an unsigned APK.
   *
   * @return A `PathRef` to the generated unsigned APK file (`app.unsigned.apk`).
   */
  def androidUnsignedApk: T[PathRef] = Task {
    val unsignedApk: os.Path = T.dest / "app.unsigned.apk"
    os.copy.over((T.dest / os.up / "androidResources.dest" / "res.apk"), unsignedApk)
    os.call(Seq(
      "zip",
      "-j",
      unsignedApk.toString,
      s"${androidDex().path / "classes.dex"}"
    ))

    PathRef(unsignedApk)
  }

  /**
   * Optimizes the APK using the `zipalign` tool for better performance.
   *
   * For more details on the zipalign tool, refer to:
   * [[https://developer.android.com/tools/zipalign zipalign Documentation]]
   */
  def androidAlignedUnsignedApk: T[PathRef] = Task {
    val alignedApk: os.Path = T.dest / "app.aligned.apk"

    os.call(
      Seq(
        androidSdkModule().zipalignPath().path.toString, // Call zipalign tool
        "-f",
        "-p",
        "4", // Force overwrite, align with 4-byte boundary
        androidUnsignedApk().path.toString, // Use the unsigned APK
        alignedApk.toString // Output aligned APK
      )
    )

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
    val signedApk: os.Path = T.dest / "app.apk"

    os.call(
      Seq(
        androidSdkModule().apksignerPath().path.toString,
        "sign", // Call apksigner tool
        "--ks",
        androidKeystore().path.toString, // Path to keystore
        "--ks-key-alias",
        "androidkey", // Key alias
        "--ks-pass",
        "pass:android", // Keystore password
        "--key-pass",
        "pass:android", // Key password
        "--out",
        signedApk.toString, // Output signed APK
        androidAlignedUnsignedApk().path.toString // Use aligned APK
      )
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
    val keystoreFile: os.Path = T.dest / "keystore.jks"

    if (!os.exists(keystoreFile)) {
      os.call(
        Seq(
          "keytool",
          "-genkeypair",
          "-keystore",
          keystoreFile.toString, // Generate keystore
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
        )
      )
    }

    PathRef(keystoreFile)
  }
}
