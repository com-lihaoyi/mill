package mill.javalib.android 

import mill._
import mill.scalalib._
import mill.api.PathRef

/**
 * Trait that provides functionality for building Android applications.
 * 
 * This trait extends `JavaModule`, which offers basic Java project structure. It includes methods 
 * and fields for setting up the Android SDK, generating resources, compiling code, creating DEX files, 
 * APK packaging, and signing the APK.
 */
trait AndroidModule extends mill.Module with JavaModule {
  
  /** 
   * Mutable field to store the folder path passed from the app object.
   * This folder likely represents the current working directory or some project-specific path.
   */
  var currentFolder: Option[os.Path] = None

  /** 
   * Mutable field to store the application name passed by the user. 
   * Default value is `"helloworld"`.
   */
  var appName: String = "helloworld" // Default app name

  /** 
   * Defines the Android SDK Build Tools version.
   * This version is used to locate the correct Android build tools when compiling the project.
   */
  val androidBuildToolsVersion = "35.0.0"
  
  /** 
   * Defines the Android Platform version. 
   * This version is used to specify the API level target for the Android build.
   */
  val androidPlatformVersion = "android-35"
  
  /** 
   * Temporary path for the Android SDK directory. 
   * This path will be set later, likely based on user configuration or environment detection.
   */
  var sdkDir: os.Path = os.pwd
  
  /** 
   * Temporary path for the D8 tool, which is used for compiling Java bytecode into DEX bytecode.
   * This path will be set later as well.
   */
  var d8Path: os.Path = os.pwd
  
  /**
   * Detects if the current operating system is Windows.
   * 
   * return true if the operating system is Windows, false otherwise.
   */
  val osName: String = sys.props("os.name").toLowerCase
  val isWindows: Boolean = osName.contains("windows")
  
  /** 
   * Path to the D8 tool depending on the operating system.
   * 
   * If the OS is Windows, the path includes `d8.bat`, otherwise it is just `d8`.
   */
  var d8 = if (isWindows) {
    os.rel / "build-tools" / androidBuildToolsVersion / "d8.bat"
  } else {
    os.rel / "build-tools" / androidBuildToolsVersion / "d8"
  }
  
  /** 
   * Path to the APK signer tool depending on the operating system.
   * 
   * On Windows, the path includes `apksigner.bat`, otherwise it uses `apksigner`.
   */
  var apksigner = if (isWindows) {
    os.rel / "build-tools" / androidBuildToolsVersion / "apksigner.bat"
  } else {
    os.rel / "build-tools" / androidBuildToolsVersion / "apksigner"
  }


  /**
   * A method that allows users to set both the path and optionally the app name.
   * If the app name is not provided, it defaults to "helloworld".
   * If the path is not provided, a default path based on the operating system is used.
   *
   * param --path The optional path to the folder. If not provided, a default path is used.
   * param --name The optional application name. Defaults to "helloworld".
   */
  def setConfig(path: Option[String] = None, name: Option[String] = None) = T.command {
    
    val defaultPath = os.Path(millSourcePath.toString.replace("\\app", "\\")) // Default path logic
    val filePath = path.map(os.Path(_)).getOrElse(defaultPath) // Determine file path
    
    // Check if the path exists
    if (!os.exists(filePath)) {
      throw new java.io.IOException(s"Path $filePath does not exist!")
    }

    // Set the current folder
    currentFolder = Some(filePath)

    // Set the app name, defaulting to "helloworld" if not provided
    appName = name.getOrElse("helloworld")
  }

  /** 
   * A helper method to validate that the path is set.
   * 
   * If the current folder is not set, it throws a runtime exception indicating 
   * that the user needs to provide a valid path.
   */
  def ensurePathIsSet(): os.Path = {
    currentFolder match {
      case Some(path) => path
      case None =>
        throw new RuntimeException("User path is not set! Please provide a valid path using 'app.setUserPath'")
    }
  }

  /**
   * Provides the URL for downloading the Android command line tools based on the current operating system.
   * 
   * The method returns the appropriate download URL depending on whether the OS is Windows or Linux.
   */
  def commandLineToolsUrl: String = {
    if (isWindows) {
      "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    } else {
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    }
  }


  /**
   * Downloads and sets up the Android SDK components, including command line tools, platform-tools,
   * build-tools, and platforms. Ensures that the required SDK licenses are accepted.
   * 
   * This method performs the following steps:
   * 1. Creates the SDK directory if it doesn't exist.
   * 2. Downloads the Android command line tools, if not already present.
   * 3. Extracts the downloaded tools (ZIP format).
   * 4. Creates the required SDK license file.
   * 5. Installs platform-tools, build-tools, and platform components using the `sdkmanager` tool.
   */
  def downloadAndroidSdkComponents: T[Unit] = T {
    sdkDir = T.dest / "android-sdk"
    os.makeDir.all(sdkDir)

    // Download command line tools
    val toolsZip = commandLineToolsUrl.split("/").last
    val toolsPath = sdkDir / toolsZip

    if (!os.exists(toolsPath)) {
      println(s"Downloading command line tools from $commandLineToolsUrl...")
      os.write(toolsPath, requests.get(commandLineToolsUrl).bytes)

      // Extract downloaded tools based on the operating system
      if (isWindows) {
        os.proc(
          "powershell",
          "Expand-Archive",
          "-Path", toolsPath.toString,
          "-DestinationPath", sdkDir.toString
        ).call()
      } else {
        os.proc("unzip", toolsPath.toString, "-d", sdkDir.toString).call()
      }
    } else {
      println("Command line tools already exist, skipping download.")
    }

    // Unzipped tools directory path
    val toolsPathUnzipped = sdkDir / "cmdline-tools" / "bin"
    val sdkManagerPath = if (isWindows) {
      toolsPathUnzipped / "sdkmanager.bat"
    } else {
      toolsPathUnzipped / "sdkmanager"
    }

    // Create the license file with the accepted license hash
    val licenseHash = "24333f8a63b6825ea9c5514f83c2829b004d1fee"
    val licensesDir = sdkDir / "licenses"
    os.makeDir.all(licensesDir)
    os.write(licensesDir / "android-sdk-license", licenseHash)

    // Install platform-tools, build-tools, and platforms using the sdkmanager
    os.proc(
      sdkManagerPath.toString,
      "--sdk_root=" + sdkDir.toString,
      "platform-tools",
      s"build-tools;$androidBuildToolsVersion",
      s"platforms;$androidPlatformVersion"
    ).call()
  }

  /**
   * Generates Android-specific resources using AAPT (Android Asset Packaging Tool).
   * 
   * This includes compiling the resources defined in the `AndroidManifest.xml` file and generating 
   * corresponding Java files.
   * 
   * return A `PathRef` to the generated resources directory.
   */
  def generateResources = T {
    downloadAndroidSdkComponents()
    val aaptPath = sdkDir / "build-tools" / androidBuildToolsVersion / "aapt"
    val genDir = T.dest / "gen"
    os.makeDir.all(genDir)
    os.proc(
      aaptPath,
      "package",
      "-f",
      "-m",
      "-J", genDir,
      "-M", ensurePathIsSet() / "AndroidManifest.xml",
      "-I", sdkDir / "platforms" / androidPlatformVersion / "android.jar"
    ).call()
    PathRef(genDir)
  }

  /**
   * Compiles the Java source files and Android-generated resources into class files.
   * 
   * This method uses `javac` to compile both user-provided Java source files and the Java 
   * files generated by the `generateResources` method. The Android SDK's platform jar is 
   * included in the classpath for proper classpath resolution.
   * 
   * return A `PathRef` to the directory containing the compiled class files.
   */
  def compileJava = T {
    val genPath = generateResources().path
    val objDir = T.dest / "obj"
    os.makeDir.all(objDir)
    os.proc(
      "javac",
      "-classpath", sdkDir / "platforms" / androidPlatformVersion / "android.jar",
      "-d", objDir,
      os.walk(genPath).filter(_.ext == "java"),           // Generated Java files
      os.walk(ensurePathIsSet() / "java").filter(_.ext == "java")  // User's Java files
    ).call()
    PathRef(objDir)
  }

  /**
   * Packages the compiled Java class files into a jar using the D8 tool.
   * 
   * This jar contains the compiled bytecode, which will later be converted to DEX format.
   * 
   * return A `PathRef` to the generated jar file.
   */
  def createJar = T {
    val compilePath = compileJava().path
    d8Path = sdkDir / d8
    val jarPath = T.dest / "my_classes.jar"
    os.proc(
      d8Path,
      os.walk(compilePath).filter(_.ext == "class"),  // Include all .class files
      "--output", jarPath,
      "--no-desugaring"  // Disable desugaring for compatibility
    ).call()
    PathRef(jarPath)
  }

  /**
   * Compiles the Java source code and packages it into a jar using the D8 compiler.
   * 
   * The output is an Android-specific DEX file that can be included in an APK package.
   * 
   * return A `PathRef` to the DEX output.
   */
  def createDex = T {
    val jarPath = createJar().path
    val dexPath = T.dest
    os.proc(
      d8Path,
      jarPath,  // Use the generated jar file
      sdkDir / "platforms" / androidPlatformVersion / "android.jar",  // Android platform jar
      "--output", dexPath
    ).call()
    PathRef(dexPath)
  }

  /**
   * Creates an APK package from the compiled DEX file and the app's resources.
   * 
   * The APK created by this method is unsigned and contains both the compiled DEX file and 
   * the application resources defined in the `AndroidManifest.xml`.
   * 
   * return A `PathRef` to the unsigned APK.
   */
  def createApk = T {
    val dexPath = createDex().path
    val aaptPath = sdkDir / "build-tools" / androidBuildToolsVersion / "aapt"
    val apkPath = T.dest / s"${appName}.unsigned.apk"
    os.proc(
      aaptPath,
      "package",
      "-f",
      "-M", ensurePathIsSet() / "AndroidManifest.xml",  // App manifest
      "-I", sdkDir / "platforms" / androidPlatformVersion / "android.jar",  // Platform jar
      "-F", apkPath,  // Output unsigned APK
      dexPath  // Include compiled DEX file
    ).call()
    PathRef(apkPath)
  }

  /**
   * Aligns the APK using the zipalign tool, optimizing it for faster loading on Android devices.
   * 
   * This step ensures that all uncompressed data in the APK is aligned on 4-byte boundaries, 
   * which improves the efficiency of loading the APK on Android devices.
   * 
   * return A `PathRef` to the aligned APK file.
   */
  def alignApk = T {
    val createApkPath = createApk().path
    val zipAlignPath = sdkDir / "build-tools" / androidBuildToolsVersion / "zipalign"
    val alignedApkPath = T.dest / s"${appName}.aligned.apk"
    os.proc(
      zipAlignPath,
      "-f",  // Force overwrite if the output file already exists
      "-p",  // Enable page alignment for the APK
      "4",   // Align on 4-byte boundaries
      createApkPath,
      alignedApkPath
    ).call()
    PathRef(alignedApkPath)
  }

  /**
   * Creates a keystore if one doesn't exist, used for signing the APK.
   * 
   * The keystore is generated with a default password and alias for signing the APK. 
   * The `keytool` utility is used to generate a keypair and store it in a Java KeyStore (JKS) format.
   * 
   * return A `PathRef` to the generated keystore file.
   */
  def createKeystore = T {
    val keystorePath = T.dest / "keystore.jks"
    if (!os.exists(keystorePath)) {
      os.proc(
        "keytool",
        "-genkeypair",  // Generate a key pair
        "-keystore", keystorePath,  // Path to store the keystore
        "-alias", "androidkey",  // Alias name for the keypair
        "-dname", "CN=MILL, OU=MILL, O=MILL, L=MILL, S=MILL, C=IN",  // Distinguished name fields
        "-validity", "10000",  // Validity of the key in days
        "-keyalg", "RSA",  // Algorithm to be used for the key generation
        "-keysize", "2048",  // Key size in bits
        "-storepass", "android",  // Password for the keystore
        "-keypass", "android"  // Password for the keypair
      ).call()
    }
    PathRef(keystorePath)
  }

  /**
   * Signs the aligned APK using the generated keystore, making it a valid Android package
   * that can be installed on Android devices.
   * 
   * The `apksigner` tool is used to apply the cryptographic signature to the APK, which ensures 
   * the integrity and authenticity of the package.
   * 
   * return A `PathRef` to the signed APK file.
   */
  def createApp = T {
    val apkAlignPath = alignApk().path
    val keyStorePath = createKeystore().path
    val apksignerPath: os.Path = sdkDir / apksigner
    val signedApkPath = ensurePathIsSet() / s"${appName}.apk"
    os.proc(
      apksignerPath,
      "sign",  // Command to sign the APK
      "--ks", keyStorePath,  // Path to the keystore
      "--ks-key-alias", "androidkey",  // Key alias in the keystore
      "--ks-pass", "pass:android",  // Keystore password
      "--key-pass", "pass:android",  // Key password
      "--out", signedApkPath,  // Path for the signed APK
      apkAlignPath  // Path to the aligned APK
    ).call()
    PathRef(signedApkPath)
  }
}

/**
 * ## Android APK Build Process
 *
 * This code automates the process of building an Android APK using the Mill build tool. 
 * It covers the entire build flow from resource generation to APK packaging, alignment, and signing.
 * 
 * ### Steps:
 * 
 * 1. **Setting up the Android SDK**
 *    - `downloadAndroidSdkComponents`
 *    - Downloads the Android SDK components (command-line tools, platform-tools, etc.) and sets up the environment.
 * 
 * 2. **Generating Android Resources**
 *    - `generateResources`
 *    - Compiles resources defined in the `AndroidManifest.xml` file using AAPT and generates necessary Java files.
 * 
 * 3. **Compiling Java Source Files**
 *    - `compileJava`
 *    - Compiles the user-defined Java source files and the Android-generated resources into class files.
 * 
 * 4. **Packaging into a JAR**
 *    - `createJar`
 *    - Packages the compiled Java class files into a JAR using the D8 tool. This JAR will later be converted into DEX format.
 * 
 * 5. **Converting JAR to DEX**
 *    - `createDex`
 *    - Converts the JAR file into an Android DEX file, which is executable on Android devices.
 * 
 * 6. **Creating the APK**
 *    - `createApk`
 *    - Packages the DEX file and resources into an unsigned APK.
 * 
 * 7. **Aligning the APK**
 *    - `alignApk`
 *    - Aligns the APK using the `zipalign` tool to optimize it for faster loading on Android devices.
 * 
 * 8. **Creating a Keystore**
 *    - `createKeystore`
 *    - Generates a keystore (if one doesn't already exist) for signing the APK.
 * 
 * 9. **Signing the APK**
 *    - `createApp`
 *    - Signs the APK with the keystore, creating a valid, installable APK for Android devices.
 * 
 * ### Process Flow:
 * 
 * `downloadAndroidSdkComponents` → `generateResources` → `compileJava` → `createJar` → `createDex` → `createApk` → `alignApk` → `createKeystore` → `createApp`
 */
