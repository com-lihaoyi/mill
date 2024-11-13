package mill.javalib.android

import mill._
import mill.scalalib._
import mill.api.PathRef
import mill.javalib.android.AndroidAppModule

/**
 * A Trait for Android App Bundle Creation
 */
@mill.api.experimental
trait AndroidAppBundle extends AndroidAppModule with JavaModule {

  /**
   * Adds `--proto-format` option to AAPT commands.
   */
  override def androidAaptOptions: T[Seq[String]] = Task {
    super.androidAaptOptions() ++ Seq[String]("--proto-format")
  }

  /**
   * Creates a zip with compiled resources and DEX files for the app bundle.
   *
   * @return PathRef to the bundle zip.
   *
   * For Bundle Zip Format please See the official
   * [[https://developer.android.com/guide/app-bundle/app-bundle-format Documentation]]
   */
  def androidBundleZip: T[PathRef] = Task {
    val dexFile = androidDex().path
    val resFile = androidResources().path / "res.apk"
    val baseDir = Task.dest / "base"
    val appDir = Task.dest / "app"

    os.copy(dexFile, Task.dest / dexFile.last)
    os.unzip(resFile, appDir)
    os.copy(
      appDir / "AndroidManifest.xml",
      baseDir / "manifest/AndroidManifest.xml",
      createFolders = true
    )

    for (file <- os.walk(appDir).filter(_.ext == "pb")) {
      os.copy(file, baseDir / file.last)
    }

    for ((file, idx) <- os.walk(Task.dest).filter(_.ext == "dex").zipWithIndex) {
      os.copy(file, baseDir / "dex" / file.last, createFolders = true)
    }

    for (folder <- Seq("res", "lib", "assets")) {
      val folderPath = appDir / folder
      if (os.exists(folderPath)) {
        os.copy.over(folderPath, baseDir / folder)
      }
    }

    os.zip(Task.dest / "bundle.zip", Seq(baseDir))

    PathRef(Task.dest / "bundle.zip")
  }

  /**
   * Builds an unsigned Android App Bundle (AAB) from the bundle zip.
   *
   * @return PathRef to the unsigned AAB.
   *
   * For Process Please Read the Official
   * [[https://developer.android.com/build/building-cmdline Documentation]]
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
   * Signs the AAB using the specified keystore.
   *
   * @return PathRef to the signed AAB.
   *
   * For More Please see JarSigner
   * [[https://docs.oracle.com/javase/8/docs/technotes/tools/windows/jarsigner.html Documentation]]
   */
  def androidBundle: T[PathRef] = Task {
    val signedBundle = Task.dest / "signedBundle.aab"
    val keyPath = {
      if (!os.exists(androidReleaseKeyPath().path)) { androidKeystore().path }
      else { androidReleaseKeyPath().path }
    }
    os.call((
      "jarsigner",
      "-sigalg",
      "SHA256withRSA",
      "-digestalg",
      "SHA-256",
      "-keypass",
      androidReleaseKeyPass(),
      "-storepass",
      androidReleaseKeyStorePass(),
      "-keystore",
      keyPath,
      "-signedjar",
      signedBundle,
      androidUnsignedBundle().path,
      androidReleaseKeyAlias()
    ))

    PathRef(signedBundle)
  }

}
