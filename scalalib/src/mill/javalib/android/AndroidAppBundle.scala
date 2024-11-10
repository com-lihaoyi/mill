package mill.javalib.android

import mill._
import mill.scalalib._
import mill.api.PathRef
import mill.javalib.android.AndroidAppModule

/**
 * Defines an Android app bundle module with tasks for resource compilation,
 * bundling, and APK generation using AAPT2 and bundle tool.
 */
@mill.api.experimental
trait AndroidAppBundle extends AndroidAppModule with JavaModule {

  /**
   * URL to download AAPT2, a tool for compiling and linking Android resources.
   */
  def aapt2Url: T[String] = Task {
    "https://dl.google.com/android/maven2/com/android/tools/build/aapt2/8.7.2-12006047/aapt2-8.7.2-12006047-linux.jar"
  }

  /**
   * URL to download bundle tool, used for creating Android app bundles (AAB files).
   */
  def bundleToolUrl: T[String] = Task {
    "https://github.com/google/bundletool/releases/download/1.17.2/bundletool-all-1.17.2.jar"
  }

  /**
   * Compiles Android resources using AAPT2, linking them into a single archive
   * to be included in the app bundle.
   *
   * @return PathRef to the compiled resources directory.
   */
  def androidResources: T[PathRef] = Task {
    // Define paths for the AAPT2 jar and extracted tool
    val aapt2Jar = Task.dest / "aapt2.jar"
    val aapt2 = Task.dest / "aapt2"
    val compiledResDir = Task.dest / "compiled"

    // Get resource directories and filter for existing ones
    val resourceDirs = resources().map(_.path).filter(os.exists)

    // Download and extract AAPT2 tool
    os.write(aapt2Jar, requests.get(aapt2Url()).bytes)
    os.unzip(aapt2Jar, Task.dest)
    os.call(("chmod", "+x", aapt2)) // Make AAPT2 executable
    os.makeDir.all(compiledResDir) // Ensure compiled resource directory exists

    // Compile each resource directory using AAPT2
    var count = 0
    val compiledZips = resourceDirs.map { resDir =>
      val outputZip = compiledResDir / s"${resDir.last}-${count}.zip"
      count += 1
      os.call((
        aapt2,
        "compile",
        "--dir",
        resDir,
        "-o",
        outputZip
      ))
      outputZip
    }

    // Separate main resource zip and library zips for later linking
    val ResourceZip = compiledZips.find(_.toString.contains("resources"))
    val libzips = compiledZips.filterNot(_.toString.contains("resources"))
    val compiledlibs = libzips.flatMap(zip => Seq("-R", zip.toString))

    // Link resources and generate res.apk
    os.call(
      Seq(
        aapt2.toString, // AAPT2 executable path
        "link",
        "-I",
        androidSdkModule().androidJarPath().path.toString, // Android SDK jar
        "--auto-add-overlay",
        "--proto-format" // Format and overlay options
      ) ++ compiledlibs ++ Seq(
        "--manifest",
        androidManifest().path.toString, // Use provided manifest
        "--java",
        Task.dest.toString, // Output generated R.java
        "-o",
        s"${Task.dest / "res.apk"}", // Output APK path
        ResourceZip.map(_.toString).getOrElse("")
      )
    )

    PathRef(Task.dest) // Return compiled resource directory as PathRef
  }

  /**
   * Packages compiled resources and dex files into a zip for app bundle generation.
   *
   * @return PathRef to the created bundle zip.
   */
  def androidBundleZip: T[PathRef] = Task {
    val dexFile = androidDex().path
    val baseDir = Task.dest / "base"

    // Create directory structure for Android bundle
    Seq("manifest", "dex").foreach(d => os.makeDir.all(baseDir / d))

    // Copy dex and resource files into the bundle directory
    os.copy(dexFile, Task.dest / dexFile.last)
    os.unzip(Task.dest / os.up / "androidResources.dest" / "res.apk", Task.dest)
    os.copy(Task.dest / "AndroidManifest.xml", baseDir / "manifest" / "AndroidManifest.xml")

    // Copy .pb files (compiled protobuf resources) and organize dex files
    os.walk(Task.dest).filter(_.ext == "pb").foreach(file => os.copy(file, baseDir / file.last))
    os.walk(Task.dest).filter(_.ext == "dex").zipWithIndex.foreach { case (file, idx) =>
      os.copy(file, baseDir / "dex" / (if (idx == 0) "classes.dex" else s"classes${idx + 1}.dex"))
    }

    // Copy additional resources (res, lib, assets) if present
    Seq("res", "lib", "assets").foreach { folder =>
      val folderPath = Task.dest / folder
      if (os.exists(folderPath)) {
        os.copy.over(folderPath, baseDir / folder)
      }
    }

    // Create the final bundle zip
    os.zip(Task.dest / "bundle.zip", Seq(baseDir))

    PathRef(Task.dest / "bundle.zip")
  }

  /**
   * Creates an Android App Bundle (AAB) using bundle tool from the bundle zip.
   *
   * @return PathRef to the created app bundle (AAB).
   */
  def androidBundle: T[PathRef] = Task {
    val bundleToolJar = Task.dest / "bundleTool.jar"
    val bundleFile = Task.dest / "bundle.aab"
    val zipPath = androidBundleZip().path

    // Download bundle tool and generate .aab using the bundle zip
    os.write(bundleToolJar, requests.get(bundleToolUrl()).bytes)
    os.call((
      "java",
      "-jar",
      bundleToolJar,
      "build-bundle",
      s"--modules=${zipPath}",
      s"--output=${bundleFile}"
    ))

    PathRef(bundleFile)
  }

  /**
   * Signs the Android App Bundle (AAB) using the provided keystore.
   *
   * @return PathRef to the signed app bundle.
   */
  def signBundle: T[PathRef] = Task {
    val signedBundle = Task.dest / "signed_bundle.aab"

    // Use jarsigner to sign the AAB file
    os.call((
      "jarsigner",
      "-verbose",
      "-sigalg",
      "SHA256withRSA",
      "-digestalg",
      "SHA-256",
      "-storepass",
      "android",
      "-keystore",
      androidKeystore().path,
      "-signedjar",
      signedBundle,
      androidBundle().path,
      "androidkey"
    ))

    PathRef(signedBundle)
  }

  /**
   * Generates a universal APK from the signed Android App Bundle (AAB).
   *
   * @return PathRef to the generated universal APK.
   */
  def androidApk: T[PathRef] = Task {
    val apkSet = Task.dest / "app.apks"

    // Use bundle tool to generate APK set from the signed AAB
    os.call((
      "java",
      "-jar",
      Task.dest / os.up / "androidBundle.dest" / "bundleTool.jar",
      "build-apks",
      "--ks",
      androidKeystore().path,
      s"--bundle=${signBundle().path}",
      s"--output=$apkSet",
      "--mode=universal", // Universal APK for broader compatibility
      "--ks-key-alias",
      "androidkey",
      "--ks-pass",
      "pass:android"
    ))

    // Extract the universal APK from the APK set
    os.unzip(apkSet, Task.dest)

    PathRef(Task.dest / "universal.apk")
  }
}
