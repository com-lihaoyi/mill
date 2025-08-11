package mill.androidlib

import mill.*
import mill.api.{PathRef, Task}
import mill.javalib.*
import os.zip.ZipSource

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
    val resFile = androidLinkedResources().path / "apk/res.apk"
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

    for ((file, _) <- os.walk(Task.dest).filter(_.ext == "dex").zipWithIndex) {
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
      s"--modules=$zipPath",
      s"--output=$bundleFile"
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
    val keyPath = androidKeystore()

    // TODO this is duplicated with the parent module. Can we do it better without leaking sensitive credentials
    //  in plain to disk/console? put behind Task.Anon?
    val keystorePass = {
      if (androidIsDebug()) debugKeyStorePass else androidReleaseKeyStorePass().get
    }
    val keyAlias = {
      if (androidIsDebug()) debugKeyAlias else androidReleaseKeyAlias().get
    }
    val keyPass = {
      if (androidIsDebug()) debugKeyPass else androidReleaseKeyPass().get
    }

    os.call((
      "jarsigner",
      "-sigalg",
      "SHA256withRSA",
      "-digestalg",
      "SHA-256",
      "-keypass",
      keyPass,
      "-storepass",
      keystorePass,
      "-keystore",
      keyPath,
      "-signedjar",
      signedBundle,
      androidUnsignedBundle().path,
      keyAlias
    ))

    PathRef(signedBundle)
  }

}
