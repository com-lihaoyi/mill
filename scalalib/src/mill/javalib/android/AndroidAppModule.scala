package mill.javalib.android

import mill._
import mill.api.PathRef
import mill.scalalib.JavaModule
import mill.javalib.android.AndroidSdkModule

/**
 * Trait for creating and building Android applications using the Mill build tool.
 * This module extends `AndroidSdkModule` and `JavaModule` to provide functionality
 * for managing Android SDK tools and compiling Java code into Android APKs.
 */
trait AndroidAppModule extends AndroidSdkModule with JavaModule {

  /**
   * The current working folder for the project, derived from `millSourcePath`
   * by replacing "app" with an empty string.
   */
  var currentFolder: os.Path = os.Path(millSourcePath.toString.replace("app", ""))

  /**
   * Name of the Android application. Defaults to "HelloWorld".
   */
  var appName: String = "HelloWorld"

  /**
   * Generates the Android resources needed for the project.
   * This method uses the `aapt` tool to generate R.java files based on the
   * `AndroidManifest.xml` in the `currentFolder` and the resources available.
   * @return A `PathRef` pointing to the directory containing the generated resources.
   */
  def generateResources: T[PathRef] = T {
    installAndroidSdk()
    val genDir: os.Path = T.dest / "gen"
    os.makeDir.all(genDir)
    os.proc(
      aapt,
      "package",
      "-f",
      "-m",
      "-J",
      genDir,
      "-M",
      currentFolder / "AndroidManifest.xml",
      "-I",
      androidJarPath
    ).call()
    PathRef(genDir)
  }

  /**
   * Compiles the Java code along with the generated Android resources.
   * Uses the `javac` compiler to compile the `.java` source files from both
   * the `generateResources` task and the `currentFolder/java` folder.
   * @return A `PathRef` pointing to the directory containing compiled `.class` files.
   */
  def compileJava: T[PathRef] = T {
    val objDir: os.Path = T.dest / "obj"
    os.makeDir.all(objDir)
    os.proc(
      "javac",
      "-classpath",
      androidJarPath,
      "-d",
      objDir,
      os.walk(generateResources().path).filter(_.ext == "java"),
      os.walk(currentFolder / "java").filter(_.ext == "java")
    ).call()
    PathRef(objDir)
  }

  /**
   * Creates a JAR file containing the compiled Java classes.
   * This method uses the `d8` tool to package compiled classes into a `.jar`
   * file with desugaring disabled.
   * @return A `PathRef` pointing to the generated `.jar` file.
   */
  def createJar: T[PathRef] = T {
    val jarPath: os.Path = T.dest / "my_classes.jar"
    os.proc(
      d8,
      os.walk(compileJava().path).filter(_.ext == "class"),
      "--output",
      jarPath,
      "--no-desugaring"
    ).call()
    PathRef(jarPath)
  }

  /**
   * Creates a DEX file by converting the compiled JAR file.
   * Uses the `d8` tool to convert the classes into the Android DEX format.
   * @return A `PathRef` pointing to the directory containing the generated DEX file.
   */
  def createDex: T[PathRef] = T {
    val dexPath: os.Path = T.dest
    os.proc(
      d8,
      createJar().path,
      androidJarPath,
      "--output",
      dexPath
    ).call()
    PathRef(dexPath)
  }

  /**
   * Creates an unsigned APK from the compiled Java classes and resources.
   * Uses the `aapt` tool to package the application into an APK file.
   * @return A `PathRef` pointing to the unsigned APK file.
   */
  def createApk: T[PathRef] = T {
    val apkPath: os.Path = T.dest / s"${appName}.unsigned.apk"
    os.proc(
      aapt,
      "package",
      "-f",
      "-M",
      currentFolder / "AndroidManifest.xml",
      "-I",
      androidJarPath,
      "-F",
      apkPath,
      createDex().path
    ).call()
    PathRef(apkPath)
  }

  /**
   * Aligns the APK to optimize it for installation on Android devices.
   * Uses the `zipalign` tool to align the APK.
   * @return A `PathRef` pointing to the aligned APK file.
   */
  def alignApk: T[PathRef] = T {
    val alignedApkPath: os.Path = T.dest / s"${appName}.aligned.apk"
    os.proc(
      zipalign,
      "-f",
      "-p",
      "4",
      createApk().path,
      alignedApkPath
    ).call()
    PathRef(alignedApkPath)
  }

  /**
   * Creates a keystore if one does not already exist.
   * This method uses the `keytool` utility to generate a new keystore and key pair.
   * @return A `PathRef` pointing to the generated keystore.
   */
  def createKeystore: T[PathRef] = T {
    val keystorePath: os.Path = T.dest / "keystore.jks"
    if (!os.exists(keystorePath)) {
      os.proc(
        "keytool",
        "-genkeypair",
        "-keystore",
        keystorePath,
        "-alias",
        "androidkey",
        "-dname",
        "CN=MILL, OU=MILL, O=MILL, L=MILL, S=MILL, C=IN",
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
      ).call()
    }
    PathRef(keystorePath)
  }

  /**
   * Signs the APK using the generated or existing keystore.
   * This method uses the `apksigner` tool to sign the APK with the keystore.
   * @return A `PathRef` pointing to the signed APK file.
   */
  def createApp: T[PathRef] = T {
    val signedApkPath: os.Path = currentFolder / s"${appName}.apk"
    os.proc(
      apksigner,
      "sign",
      "--ks",
      createKeystore().path,
      "--ks-key-alias",
      "androidkey",
      "--ks-pass",
      "pass:android",
      "--key-pass",
      "pass:android",
      "--out",
      signedApkPath,
      alignApk().path
    ).call()
    PathRef(signedApkPath)
  }
}
