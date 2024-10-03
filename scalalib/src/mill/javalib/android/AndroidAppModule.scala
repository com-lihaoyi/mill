package mill.javalib.android

import mill._
import mill.api.PathRef
import mill.scalalib.JavaModule
import mill.javalib.android.AndroidSdkModule
import mill.util.Jvm

/**
 * Trait for building Android applications using Mill,
 * this extends [[AndroidSdkModule]] for Android SDK related tasks,
 * and [[JavaModule]] for Java related tasks.
 *
 * This trait outlines the steps necessary to build an Android application:
 * 1. Compile Java code into `.class` files.
 * 2. Package the `.class` files into a JAR file.
 * 3. Convert the JAR into DEX format for Android.
 * 4. Package DEX files and resources into an APK.
 * 5. Optimize the APK using zipalign.
 * 6. Sign the APK for distribution.
 *
 * For detailed information, refer to Mill's [documentation](https://com-lihaoyi.github.io/mill),
 * and the [Android developer guide](https://developer.android.com/studio).
 */
trait AndroidAppModule extends AndroidSdkModule with JavaModule {

  /**
   * Path where the Project related Files will live.
   *
   * @return A `PathRef` representing project directory.
   */
  def projectRoot: T[os.Path] = T {
    os.Path(millSourcePath.toString.replace(
      "App",
      ""
    )) // Get the parent directory of millSourcePath
  }

  /**
   * App Name for the Application default is HelloWorld.
   *
   * @return A string representing the platform version.
   */
  def appName: T[String] = T { "HelloWorld" }

  /**
   * Step 1: Compile Java source files to `.class` files.
   *
   * This method:
   * - Ensures the Android SDK is installed.
   * - Compiles all `.java` files in `src/main/java` to `.class` files stored in `obj/` directory.
   *
   * @return A `PathRef` to the directory containing the compiled `.class` files.
   *
   * @see [[createJar]]
   */
  def compileJava: T[PathRef] = T {
    installAndroidSdk() // Step 1: Install the Android SDK if not already done.
    val outputDir = T.dest / "obj" // Directory to store compiled class files.

    os.call(
      Seq(
        Jvm.jdkTool("javac"), // Use the Java compiler
        "-classpath",
        androidJarPath().path.toString, // Include Android framework classes
        "-d",
        outputDir.toString // Specify output directory for class files
      ) ++ os.walk(projectRoot() / "src/main/java").filter(_.ext == "java").map(
        _.toString
      ) // Get all Java source files
    )

    PathRef(outputDir) // Return the path to compiled class files.
  }

  /**
   * Step 2: Package `.class` files into a JAR file.
   *
   * This method:
   * - Converts the compiled `.class` files into a JAR file using the `d8` tool.
   *
   * @return A `PathRef` to the generated JAR file.
   *
   * @see [[compileJava]]
   * @see [[createDex]]
   */
  def createJar: T[PathRef] = T {
    val jarFile = T.dest / "my_classes.jar" // Specify output JAR file name.

    os.call(
      Seq(
        d8Path().path.toString, // Path to the D8 tool
        "--output",
        jarFile.toString, // Output JAR file
        "--no-desugaring" // Do not apply desugaring
      ) ++ os.walk(compileJava().path).filter(_.ext == "class").map(
        _.toString
      ) // Get compiled class files from compileJava
    )

    PathRef(jarFile) // Return the path to the created JAR file.
  }

  /**
   * Step 3: Convert the JAR file into a DEX file.
   *
   * This method:
   * - Uses the `d8` tool to convert the JAR file into DEX format, required for Android apps.
   *
   * @return A `PathRef` to the generated DEX file.
   *
   * @see [[createJar]]
   */
  def createDex: T[PathRef] = T {
    val dexOutputDir = T.dest // Directory to store DEX files.

    os.call(
      Seq(d8Path().path.toString, "--output", dexOutputDir.toString) ++ Seq(
        createJar().path.toString, // Use the JAR file from createJar
        androidJarPath().path.toString // Include Android framework classes
      )
    )

    PathRef(dexOutputDir) // Return the path to the generated DEX file.
  }

  /**
   * Step 4: Package the DEX file into an unsigned APK.
   *
   * This method:
   * - Uses the `aapt` tool to create an APK file that includes the DEX file and resources.
   *
   * @return A `PathRef` to the unsigned APK file.
   *
   * @see [[createDex]]
   */
  def createApk: T[PathRef] = T {
    val unsignedApk =
      T.dest / s"${appName().toString}.unsigned.apk" // Specify output APK file name.

    os.call(
      Seq(
        aaptPath().path.toString,
        "package", // Command to package APK
        "-f", // Force overwrite
        "-M",
        (projectRoot() / "src/main/AndroidManifest.xml").toString, // Path to the AndroidManifest.xml
        "-I",
        androidJarPath().path.toString, // Include Android framework resources
        "-F",
        unsignedApk.toString // Specify output APK file
      ) ++ Seq(createDex().path.toString) // Include the DEX file from createDex
    )

    PathRef(unsignedApk) // Return the path to the unsigned APK.
  }

  /**
   * Step 5: Optimize the APK using zipalign.
   *
   * This method:
   * - Takes the unsigned APK and optimizes it for better performance on Android devices.
   *
   * @return A `PathRef` to the aligned APK file.
   *
   * @see [[createApk]]
   */
  def alignApk: T[PathRef] = T {
    val alignedApk =
      T.dest / s"${appName().toString}.aligned.apk" // Specify output aligned APK file name.

    os.call(
      Seq(
        zipalignPath().path.toString, // Path to the zipalign tool
        "-f",
        "-p",
        "4", // Force overwrite and align with a page size of 4
        createApk().path.toString, // Use the unsigned APK from createApk
        alignedApk.toString // Specify output aligned APK file
      )
    )

    PathRef(alignedApk) // Return the path to the aligned APK.
  }

  /**
   * Step 6: Sign the APK using a keystore.
   *
   * This method:
   * - Signs the aligned APK with a keystore. If the keystore does not exist, it generates one.
   *
   * @return A `PathRef` to the signed APK file.
   *
   * @see [[alignApk]]
   * @see [[createKeystore]]
   */
  def createApp: T[PathRef] = T {
    val signedApk =
      projectRoot() / s"${appName().toString}.apk" // Specify output signed APK file name.

    os.call(
      Seq(
        apksignerPath().path.toString,
        "sign", // Command to sign APK
        "--ks",
        createKeystore().path.toString, // Use the keystore from createKeystore
        "--ks-key-alias",
        "androidkey", // Alias for the key
        "--ks-pass",
        "pass:android", // Keystore password
        "--key-pass",
        "pass:android", // Key password
        "--out",
        signedApk.toString, // Specify output signed APK file
        alignApk().path.toString // Use the aligned APK from alignApk
      )
    )

    PathRef(signedApk) // Return the path to the signed APK.
  }

  /**
   * Creates a keystore for signing APKs if it doesn't already exist.
   *
   * This method:
   * - Generates a keystore file using the `keytool` command.
   *
   * @return A `PathRef` to the keystore file.
   *
   * @see [[createApp]]
   */
  def createKeystore: T[PathRef] = T {
    val keystoreFile = T.dest / "keystore.jks" // Specify keystore file name.

    if (!os.exists(keystoreFile)) {
      os.call(
        Seq(
          "keytool",
          "-genkeypair",
          "-keystore",
          keystoreFile.toString, // Generate keystore
          "-alias",
          "androidkey", // Key alias
          "-dname",
          "CN=MILL, OU=MILL, O=MILL, L=MILL, S=MILL, C=IN", // Distinguished name
          "-validity",
          "10000", // Validity period in days
          "-keyalg",
          "RSA", // Key algorithm
          "-keysize",
          "2048", // Key size
          "-storepass",
          "android", // Keystore password
          "-keypass",
          "android" // Key password
        )
      )
    }

    PathRef(keystoreFile) // Return the path to the keystore file.
  }
}
