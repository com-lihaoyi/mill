package mill.javalib.android

import mill._
import mill.scalalib._
import mill.api.PathRef
import mill.javalib.android.AndroidAppModule

/**
 * Defines an Android app bundle module with tasks for resource compilation,
 * bundling using bundle tool.
 */
@mill.api.experimental
trait AndroidAppBundle extends AndroidAppModule with JavaModule {

  override def linkOptions: T[Seq[String]] = Task {
    super.linkOptions() ++ Seq[String](
      "--proto-format"
    ) // protoformat for resources linking(required for bundle)
  }

  /**
   * Packages compiled resources and dex files into a zip for app bundle generation.
   *
   * @return PathRef to the created bundle zip.
   */
  def androidBundleZip: T[PathRef] = Task {
    val dexFile = androidDex().path
    val resFile = androidResources().path / "res.apk"
    val baseDir = Task.dest / "base"

    // Copy dex and resource files into the bundle directory
    os.copy(dexFile, Task.dest / dexFile.last)
    os.unzip(resFile, Task.dest)
    os.copy(
      Task.dest / "AndroidManifest.xml",
      baseDir / "manifest/AndroidManifest.xml",
      createFolders = true
    )

    // Copy .pb files (compiled protobuf resources) and organize dex files using for loops
    for (file <- os.walk(Task.dest).filter(_.ext == "pb")) {
      os.copy(file, baseDir / file.last)
    }

    for ((file, idx) <- os.walk(Task.dest).filter(_.ext == "dex").zipWithIndex) {
      os.copy(
        file,
        baseDir / "dex" / (if (idx == 0) "classes.dex" else s"classes${idx + 1}.dex"),
        createFolders = true
      )
    }

    // Copy additional resources (res, lib, assets) if present using a for loop
    for (folder <- Seq("res", "lib", "assets")) {
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
  def androidUnsignedBundle: T[PathRef] = Task {
    val bundleFile = Task.dest / "bundle.aab"
    val zipPath = androidBundleZip().path

    os.call((
      "java",
      "-jar",
      androidSdkModule().bundleToolPath().path,
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
  def androidBundle: T[PathRef] = Task {
    val signedBundle = Task.dest / "signedBundle.aab"

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
      androidUnsignedBundle().path,
      "androidkey"
    ))

    PathRef(signedBundle)
  }

}
