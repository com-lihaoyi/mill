import mill._
import mill.scalalib._

object app extends JavaModule {

  // Path to AndroidManifest.xml
  def androidManifest = T { os.pwd / "AndroidManifest.xml" }

  // Path to Java sources
  def sources = T.sources { os.pwd / "java" }

  // Path to resource files (res/)
  def resources = T.sources { os.pwd / "res" }

  // Path to __build folder
  def build_path = T.sources { os.pwd / "__build" }


  // Path to the Android SDK
  def sdkDir = T {
    // First, try to get the SDK path from the ANDROID_SDK_ROOT environment variable
    sys.env.get("ANDROID_SDK_ROOT").map(os.Path(_)).getOrElse {
      // If ANDROID_SDK_ROOT is not set, fallback to local.properties
      val propsFile = os.pwd / "local.properties"
      if (os.exists(propsFile)) {
        val props = os.read(propsFile)
        val sdkPath = props.linesIterator.collectFirst {
          case line if line.startsWith("sdk.dir=") =>
            os.Path(line.stripPrefix("sdk.dir="))
        }
        sdkPath.getOrElse(
          throw new Exception("SDK path not found in local.properties")
        )
      } else {
        throw new Exception(
          "Neither ANDROID_SDK_ROOT environment variable is set, nor local.properties file is present. Please set the SDK path."
        )
      }
    }
  }



  // Android Build Tools and SDK paths
  def scalaVersion = "2.13.14"
  def targetSdkVersion = "34"
  def minSdkVersion = "9"

  // Task to generate R.java using aapt
  def generateResources = T {
    os.makeDir.all(build_path().head.path / "gen")
    os.makeDir.all(build_path().head.path / "apk")
    os.proc(
      "aapt",
      "package",
      "-f",
      "-m",
      "-J",
      build_path().head.path / "gen",
      "-S",
      resources().head.path, // Using the first path in resources()
      "-M",
      androidManifest(),
      "-I",
      s"${sdkDir()}/platforms/android-34/android.jar"
    ).call()
    T.dest
  }

  // Task to compile Java files
  def compileJava = T {
    generateResources()
    os.proc(
      "javac",
      "-classpath",
      s"${sdkDir()}/platforms/android-34/android.jar",
      "-d",
      build_path().head.path / "obj",
      os.walk(build_path().head.path / "gen").filter(_.ext == "java"), // Compile generated R.java
      os.walk(sources().head.path).filter(_.ext == "java") // Compile Java sources
    ).call()
    T.dest
  }

  // Task to create a JAR
  def createJar = T {
    compileJava()
    os.proc(
      "d8.bat",
      os.walk(build_path().head.path / "obj").filter(_.ext == "class"),
      "--output",
      build_path().head.path / "apk" / "my_classes.jar",
      "--no-desugaring"
    ).call()
    T.dest
  }

  // Task to merge the JAR with Android SDK into classes.dex
  def createDex = T {
    createJar()
    os.proc(
      "d8.bat",
      build_path().head.path / "apk" / "my_classes.jar",
      s"${sdkDir()}/platforms/android-34/android.jar",
      "--output",
      build_path().head.path / "apk"
    ).call()
    T.dest / "apk" / "classes.dex"
  }

  // Task to create APK
  def createApk = T {
    createDex()
    os.proc(
      "aapt",
      "package",
      "-f",
      "-M",
      androidManifest(),
      "-S",
      resources().head.path, // Using the first path in resources()
      "-I",
      s"${sdkDir()}/platforms/android-34/android.jar",
      "-F",
      build_path().head.path / "helloworld.unsigned.apk",
      build_path().head.path / "apk"
    ).call()
    T.dest / "helloworld.unsigned.apk"
  }

  // Task to align APK
  def alignApk = T {
    createApk()
    os.proc(
      "zipalign",
      "-f",
      "-p",
      "4",
      s"${build_path().head.path}/helloworld.unsigned.apk",
      build_path().head.path / "helloworld.aligned.apk"
    ).call()
    T.dest / "helloworld.aligned.apk"
  }

  // Task to sign the APK
  def create = T {
    alignApk()
    os.proc(
      "apksigner.bat",
      "sign",
      "--ks",
      os.pwd / "keystore.jks",
      "--ks-key-alias",
      "androidkey",
      "--ks-pass",
      "pass:android",
      "--key-pass",
      "pass:android",
      "--out",
      build_path().head.path / "helloworld.apk",
      build_path().head.path / "helloworld.aligned.apk"
    ).call()
    T.dest / "helloworld.apk"
  }
}


