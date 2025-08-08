package mill.androidlib

import mill.*
import mill.api.{PathRef, Task}
import mill.api.BuildCtx

@mill.api.experimental
trait AndroidR8AppModule extends AndroidAppModule {

  override def androidPackageMetaInfoFiles: T[Seq[AndroidPackageableExtraFile]] =
    androidR8PackageMetaInfoFiles()

  /**
   * Converts the generated JAR file into a DEX file using the r8 tool if minification is enabled
   * through the [[androidBuildSettings]].
   *
   * @return os.Path to the Generated DEX File Directory
   */
  def androidDex: T[PathRef] = Task {

    val dex = androidR8Dex()

    Task.log.debug("Building dex with command: " + dex.dexCliArgs.mkString(" "))

    os.call(dex.dexCliArgs)

    PathRef(dex.outPath.path)

  }

  /**
   * Selects the meta info and metadata files to package. These are being extracted
   * and output by R8 from the dependency jars.
   *
   * @return A list of files to package into the apk
   */
  def androidR8PackageMetaInfoFiles: T[Seq[AndroidPackageableExtraFile]] = Task {
    val root = androidDex().path

    def directoryFiles(dir: os.Path): Seq[os.Path] = if (os.exists(dir))
      os.walk(dir).filter(os.isFile)
    else
      Seq.empty[os.Path]

    val metaInfoFiles = directoryFiles(root / "META-INF")

    val kotlinMetadataFiles = directoryFiles(root / "kotlin")

    val includedFiles = (metaInfoFiles ++ kotlinMetadataFiles)

    includedFiles.map(nonDex =>
      AndroidPackageableExtraFile(PathRef(nonDex), nonDex.relativeTo(root))
    )
  }

  def androidLibraryProguardConfigs: Task[Seq[PathRef]] = Task {
    androidUnpackArchives()
      // TODO need also collect rules from other modules,
      // but Android lib module doesn't yet exist
      .flatMap(_.proguardRules)
  }

  /** ProGuard/R8 rules configuration files for release target (user-provided and generated) */
  def androidProguardConfigs: Task[Seq[PathRef]] = Task {
    val proguardFilesFromBuildSettings = androidBuildSettings().proguardFiles
    val androidProguardPath = androidSdkModule().androidProguardPath().path
    val defaultProguardFile = proguardFilesFromBuildSettings.defaultProguardFile.map {
      pf => androidProguardPath / pf
    }
    val userProguardFiles = proguardFilesFromBuildSettings.localFiles
    BuildCtx.withFilesystemCheckerDisabled {
      (defaultProguardFile.toSeq).map(
        PathRef(_)
      ) ++ userProguardFiles ++ androidLibraryProguardConfigs()
    }
  }

  /** Concatenates all rules into one file */
  override def androidProguard: T[PathRef] = Task {
    val inheritedProguardFile = super.androidProguard()

    val globalProguard = Task.dest / "global-proguard.pro"
    val files = androidProguardConfigs()
    os.write(globalProguard, os.read(inheritedProguardFile.path))

    files.foreach(pg =>
      os.write.append(globalProguard, os.read(pg.path))
    )

    PathRef(globalProguard)
  }

  /**
   * The default release settings with the following settings:
   * - minifyEnabled=true
   * - shrinkEnabled=true
   * - proguardFiles=proguard-android-optimize.txt
   *
   * @return
   */
  def androidReleaseSettings: T[AndroidBuildTypeSettings] = Task {
    AndroidBuildTypeSettings(
      isMinifyEnabled = true,
      isShrinkEnabled = true,
      proguardFiles = ProguardFiles(
        defaultProguardFile = Some("proguard-android-optimize.txt")
      )
    )
  }

  def androidR8Args: T[Seq[String]] = Task {
    Seq.empty[String]
  }

  def androidDebugSettings: T[AndroidBuildTypeSettings] = Task {
    AndroidBuildTypeSettings()
  }

  /**
   * Gives the android build type settings for debug or release.
   * Controlled by [[androidIsDebug]] flag!
   *
   * @return
   */
  def androidBuildSettings: T[AndroidBuildTypeSettings] = Task {
    if (androidIsDebug())
      androidDebugSettings()
    else
      androidReleaseSettings()
  }

  /**
   * Prepares the R8 cli command to build this android app!
   * @return
   */
  private def androidR8Dex
      : Task[(outPath: PathRef, dexCliArgs: Seq[String], appCompiledFiles: Seq[PathRef])] = Task {
    val destDir = Task.dest / "minify"
    os.makeDir.all(destDir)

    val outputPath = destDir

    Task.log.debug("outptuPath: " + outputPath)

    // Define diagnostic output file paths
    val mappingOut = destDir / "mapping.txt"
    val seedsOut = destDir / "seeds.txt"
    val usageOut = destDir / "usage.txt"
    val configOut = destDir / "configuration.txt"
    destDir / "missing_rules.txt"
    val baselineOutOpt = destDir / "baseline-profile-rewritten.txt"
    destDir / "res"

    // Create an extra ProGuard config file that instructs R8 to print seeds and usage.
    val extraRulesFile = destDir / "extra-rules.pro"
    val extraRulesContent =
      s"""-printseeds ${seedsOut.toString}
         |-printusage ${usageOut.toString}
         |""".stripMargin.trim
    os.write.over(extraRulesFile, extraRulesContent)

    val classpathClassFiles: Seq[PathRef] = androidPackagedClassfiles()
      .filter(_.path.ext == "class")

    val appCompiledFiles: Seq[PathRef] = androidPackagedCompiledClasses()
      .filter(_.path.ext == "class")

    val allClassFilesPathRefs =
      classpathClassFiles ++ appCompiledFiles ++ androidPackagedDeps()

    val allClassFiles = allClassFilesPathRefs.map(_.path.toString)

    val r8ArgsBuilder = Seq.newBuilder[String]

    r8ArgsBuilder += androidSdkModule().r8Exe().path.toString

    if (androidIsDebug())
      r8ArgsBuilder += "--debug"
    else
      r8ArgsBuilder += "--release"

    r8ArgsBuilder ++= Seq(
      "--output",
      outputPath.toString,
      "--pg-map-output",
      mappingOut.toString,
      "--pg-conf-output",
      configOut.toString
    )

    if (!androidBuildSettings().enableDesugaring) {
      r8ArgsBuilder += "--no-desugaring"
    }

    if (!androidBuildSettings().isMinifyEnabled) {
      r8ArgsBuilder += "--no-minification"
    }

    if (!androidBuildSettings().isShrinkEnabled) {
      r8ArgsBuilder += "--no-tree-shaking"
    }

    r8ArgsBuilder ++= Seq(
      "--min-api",
      androidMinSdk().toString,
      "--dex"
    )

    // Baseline profile rewriting arguments, if a baseline profile is provided.
    val baselineArgs = baselineProfile().map { bp =>
      Seq("--art-profile", bp.path.toString, baselineOutOpt.toString)
    }.getOrElse(Seq.empty)

    r8ArgsBuilder ++= baselineArgs

    // Library arguments: pass each bootclasspath and any additional library classes as --lib.
    val libArgs = libraryClassesPaths().flatMap(ref => Seq("--lib", ref.path.toString))

    r8ArgsBuilder ++= libArgs

    // ProGuard configuration files: add our extra rules file and all provided config files.
    val pgArgs = Seq("--pg-conf", androidProguard().path.toString)

    r8ArgsBuilder ++= pgArgs

    r8ArgsBuilder ++= androidR8Args()

    r8ArgsBuilder ++= allClassFiles

    val r8Args = r8ArgsBuilder.result()

    (PathRef(outputPath), r8Args, allClassFilesPathRefs)
  }

}
