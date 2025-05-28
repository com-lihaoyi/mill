package mill.androidlib

import mill.*
import mill.define.PathRef

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

    dex.outPath

  }

  /**
   * Selects the meta info and metadata files to package. These are being extracted
   * and output by R8 from the dependency jars.
   *
   * @return A list of files to package into the apk
   */
  def androidR8PackageMetaInfoFiles: T[Seq[AndroidPackageableExtraFile]] = Task {
    val root = androidDex().path
    val metaInfoFiles = os.walk(root / "META-INF")
      .filter(os.isFile).filterNot(_.ext == "dex")

    val kotlinMetadataFiles = if (os.exists(root / "kotlin"))
      os.walk(root / "kotlin").filter(os.isFile)
    else
      Seq.empty[os.Path]

    val includedFiles = (metaInfoFiles ++ kotlinMetadataFiles)

    includedFiles.map(nonDex =>
      AndroidPackageableExtraFile(PathRef(nonDex), nonDex.relativeTo(root))
    )
  }

  /**
   * Prepares the R8 cli command to build this android app!
   * @return
   */
  def androidR8Dex: Task[(outPath: PathRef, dexCliArgs: Seq[String])] = Task {
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

    val classpathClassFiles: Seq[String] = androidPackagedClassfiles()
      .filter(_.path.ext == "class")
      .map(_.path.toString)

    val appCompiledFiles: Seq[String] = androidPackagedCompiledClasses()
      .filter(_.path.ext == "class")
      .map(_.path.toString)

    val allClassFiles =
      classpathClassFiles ++ appCompiledFiles ++ androidPackagedDeps().map(_.path.toString)

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

    r8ArgsBuilder ++= allClassFiles

    val r8Args = r8ArgsBuilder.result()

    PathRef(outputPath) -> r8Args
  }

}
