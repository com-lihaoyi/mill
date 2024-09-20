import mill._, mill.scalalib._

object android extends JavaModule {

  // Android SDK paths (update these to your paths)
  def androidSdkPath = T {
    os.Path(T.env.get("ANDROID_HOME").getOrElse("C:/Users/pheno/AppData/Local/Android/sdk"))
  }

  def buildToolsVersion = T { "34.0.0" } // or your build tools version

  // Project settings
  def compileSdkVersion = T { "34" }
  def minSdkVersion = T { "21" }
  def applicationId = T { "com.helloworld.app" }

  // Java compile options
  def javacOptions = T {
    Seq("-source", "1.8", "-target", "1.8")
  }

  // Folder paths
  def buildDir = T { T.workspace / "__build" }
  def resDir = T { T.workspace / "res" }
  def javaSrcDir = T { T.workspace / "java" / "com" / "helloworld" / "app" }

  // Task to set up project structure
  def setup = T {
    os.makeDir.all(buildDir() / "gen")
    os.makeDir.all(buildDir() / "obj")
    os.makeDir.all(buildDir() / "apk")
  }

  // Task to generate R.java
  def generateRJava = T {
    setup()

    os.proc(
      androidSdkPath() / "build-tools" / buildToolsVersion() / "aapt",
      "package",
      "-f",
      "-m",
      "-J",
      buildDir() / "gen",
      "-S",
      resDir(),
      "-M",
      T.workspace / "AndroidManifest.xml",
      "-I",
      androidSdkPath() / "platforms" / "android-34" / "android.jar"
    ).call()
  }

  // Task to compile Java source files
  def compileJava = T {
    generateRJava()

    os.proc(
      "javac",
      "-classpath",
      androidSdkPath() / "platforms" / s"android-34" / "android.jar",
      "-d",
      buildDir() / "obj",
      buildDir() / "gen" / "com" / "helloworld" / "app" / "R.java",  // Path segmentation
      javaSrcDir() / "MainActivity.java"
    ).call()
  }


  // Task to convert classes to DEX format
  def convertToDex = T {
    compileJava()

    // List all `.class` files in the directory
    val classFiles = os.list(buildDir() / "obj" / "com" / "helloworld" / "app").filter(_.ext == "class")

    os.proc(
      androidSdkPath() / "build-tools" / buildToolsVersion() / "d8.bat" ,
      classFiles,  // Pass the list of class files
      "--output",
      buildDir() / "apk" / "my_classes.jar",
      "--no-desugaring"
    ).call()

    os.proc(
      androidSdkPath() / "build-tools" / buildToolsVersion() / "d8.bat" ,
      androidSdkPath() / "platforms" / s"android-$compileSdkVersion" / "android.jar",
      buildDir() / "apk" / "my_classes.jar"
    ).call()
  }
  
  // Task to package the APK
  def packageApk = T {
    convertToDex()

    os.proc(
      androidSdkPath() / "build-tools" / buildToolsVersion() / "aapt",
      "package",
      "-f",
      "-M",
      T.workspace / "AndroidManifest.xml",
      "-S",
      resDir(),
      "-I",
      androidSdkPath() / "platforms" / s"android-34" / "android.jar",
      "-F",
      buildDir() / "helloworld.unsigned.apk",
      buildDir() / "apk"
    ).call()
  }

  // Task to align the APK
  def alignApk = T {
    packageApk()

    os.proc(
      androidSdkPath() / "build-tools" / buildToolsVersion() / "zipalign",
      "-f",
      "-p",
      "4",
      buildDir() / "helloworld.unsigned.apk",
      buildDir() / "helloworld.aligned.apk"
    ).call()
  }

  // Task to sign the APK
  def signApk = T {
    alignApk()

    os.proc(
      androidSdkPath() / "build-tools" / buildToolsVersion() / "apksigner",
      "sign",
      "--ks",
      T.workspace / "keystore.jks",
      "--ks-key-alias",
      "androidkey",
      "--ks-pass",
      "pass:android",
      "--key-pass",
      "pass:android",
      "--out",
      buildDir() / "helloworld.apk",
      buildDir() / "helloworld.aligned.apk"
    ).call()

    PathRef(buildDir() / "helloworld.apk")
  }

  // Main target to build APK
  def apk = T { signApk() }
}
