package mill.javalib.android 

import mill._
import mill.scalalib._
import mill.api.PathRef

trait AndroidModule extends mill.Module with JavaModule {
  var currentFolder: Option[os.Path] = None

  var appName: String = "helloworld" 

  val androidBuildToolsVersion = "35.0.0"
  
  val androidPlatformVersion = "android-35"
  
  var sdkDir: os.Path = os.pwd
  
  var d8Path: os.Path = os.pwd
  
  val osName: String = sys.props("os.name").toLowerCase
  val isWindows: Boolean = osName.contains("windows")
  
  var d8 = if (isWindows) {
    os.rel / "build-tools" / androidBuildToolsVersion / "d8.bat"
  } else {
    os.rel / "build-tools" / androidBuildToolsVersion / "d8"
  }
  
  var apksigner = if (isWindows) {
    os.rel / "build-tools" / androidBuildToolsVersion / "apksigner.bat"
  } else {
    os.rel / "build-tools" / androidBuildToolsVersion / "apksigner"
  }

  def setConfig(path: Option[String] = None, name: Option[String] = None) = T.command {
    
    val defaultPath = os.Path(millSourcePath.toString.replace("\\app", "\\")) 
    val filePath = path.map(os.Path(_)).getOrElse(defaultPath) 
    
    if (!os.exists(filePath)) {
      throw new java.io.IOException(s"Path $filePath does not exist!")
    }

    currentFolder = Some(filePath)

    appName = name.getOrElse("helloworld")
  }

  def ensurePathIsSet(): os.Path = {
    currentFolder match {
      case Some(path) => path
      case None =>
        throw new RuntimeException("User path is not set! Please provide a valid path using 'app.setUserPath'")
    }
  }

  def commandLineToolsUrl: String = {
    if (isWindows) {
      "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    } else {
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    }
  }

  def downloadAndroidSdkComponents: T[Unit] = T {
    sdkDir = T.dest / "android-sdk"
    os.makeDir.all(sdkDir)

    val toolsZip = commandLineToolsUrl.split("/").last
    val toolsPath = sdkDir / toolsZip

    if (!os.exists(toolsPath)) {
      println(s"Downloading command line tools from $commandLineToolsUrl...")
      os.write(toolsPath, requests.get(commandLineToolsUrl).bytes)

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

    val toolsPathUnzipped = sdkDir / "cmdline-tools" / "bin"
    val sdkManagerPath = if (isWindows) {
      toolsPathUnzipped / "sdkmanager.bat"
    } else {
      toolsPathUnzipped / "sdkmanager"
    }

    val licenseHash = "24333f8a63b6825ea9c5514f83c2829b004d1fee"
    val licensesDir = sdkDir / "licenses"
    os.makeDir.all(licensesDir)
    os.write(licensesDir / "android-sdk-license", licenseHash)

    os.proc(
      sdkManagerPath.toString,
      "--sdk_root=" + sdkDir.toString,
      "platform-tools",
      s"build-tools;$androidBuildToolsVersion",
      s"platforms;$androidPlatformVersion"
    ).call()
  }

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

  def compileJava = T {
    val genPath = generateResources().path
    val objDir = T.dest / "obj"
    os.makeDir.all(objDir)
    os.proc(
      "javac",
      "-classpath", sdkDir / "platforms" / androidPlatformVersion / "android.jar",
      "-d", objDir,
      os.walk(genPath).filter(_.ext == "java"),
      os.walk(ensurePathIsSet() / "java").filter(_.ext == "java")
    ).call()
    PathRef(objDir)
  }

  def createJar = T {
    val compilePath = compileJava().path
    d8Path = sdkDir / d8
    val jarPath = T.dest / "my_classes.jar"
    os.proc(
      d8Path,
      os.walk(compilePath).filter(_.ext == "class"),  
      "--output", jarPath,
      "--no-desugaring"  
    ).call()
    PathRef(jarPath)
  }

  def createDex = T {
    val jarPath = createJar().path
    val dexPath = T.dest
    os.proc(
      d8Path,
      jarPath,
      sdkDir / "platforms" / androidPlatformVersion / "android.jar",
      "--output", dexPath
    ).call()
    PathRef(dexPath)
  }

  def createApk = T {
    val dexPath = createDex().path
    val aaptPath = sdkDir / "build-tools" / androidBuildToolsVersion / "aapt"
    val apkPath = T.dest / s"${appName}.unsigned.apk"
    os.proc(
      aaptPath,
      "package",
      "-f",
      "-M", ensurePathIsSet() / "AndroidManifest.xml",
      "-I", sdkDir / "platforms" / androidPlatformVersion / "android.jar",
      "-F", apkPath,
      dexPath
    ).call()
    PathRef(apkPath)
  }

  def alignApk = T {
    val createApkPath = createApk().path
    val zipAlignPath = sdkDir / "build-tools" / androidBuildToolsVersion / "zipalign"
    val alignedApkPath = T.dest / s"${appName}.aligned.apk"
    os.proc(
      zipAlignPath,
      "-f",
      "-p",
      "4",
      createApkPath,
      alignedApkPath
    ).call()
    PathRef(alignedApkPath)
  }

  def createKeystore = T {
    val keystorePath = T.dest / "keystore.jks"
    if (!os.exists(keystorePath)) {
      os.proc(
        "keytool",
        "-genkeypair",
        "-keystore", keystorePath,
        "-alias", "androidkey",
        "-dname", "CN=MILL, OU=MILL, O=MILL, L=MILL, S=MILL, C=IN",
        "-validity", "10000",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-storepass", "android",
        "-keypass", "android"
      ).call()
    }
    PathRef(keystorePath)
  }

  def createApp = T {
    val apkAlignPath = alignApk().path
    val keyStorePath = createKeystore().path
    val apksignerPath: os.Path = sdkDir / apksigner
    val signedApkPath = ensurePathIsSet() / s"${appName}.apk"
    os.proc(
      apksignerPath,
      "sign",
      "--ks", keyStorePath,
      "--ks-key-alias", "androidkey",
      "--ks-pass", "pass:android",
      "--key-pass", "pass:android",
      "--out", signedApkPath,
      apkAlignPath
    ).call()
    PathRef(signedApkPath)
  }
}