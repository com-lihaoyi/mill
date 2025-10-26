package mill.androidlib

import mill.*
import mill.api.{PathRef, Task}
import scala.xml.*

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
    androidUnpackRunArchives()
      // TODO need also collect rules from other modules,
      // but Android lib module doesn't yet exist
      .flatMap(_.proguardRules)
  }

  /**
   * The ProGuard/R8 rules configuration files for the Android project.
   * @return
   */
  def androidProjectProguardFiles: T[Seq[PathRef]] = Task.Sources()

  /** ProGuard/R8 rules configuration files for release target (user-provided and generated) */
  def androidProguardConfigs: T[Seq[PathRef]] = Task {
    androidDefaultProguardFiles() ++ androidProjectProguardFiles() ++ androidLibraryProguardConfigs()
  }

  /**
   * Creates a file for letting know R8 that [[compileModuleDeps]] and
   * [[compileMvnDeps]] are in compile classpath only and not packaged with the apps.
   * Useful for dependencies that are provided in devices and compile only module deps
   * such as for avoiding to package main sources in the androidTest apk.
   */
  def androidR8CompileOnlyClasspath: T[Option[PathRef]] = Task {
    val resolvedCompileMvnDeps =
      androidResolvedCompileMvnDeps() ++ androidTransitiveCompileOnlyClasspath() ++ androidTransitiveModuleRClasspath()
    if (!resolvedCompileMvnDeps.isEmpty) {
      val compiledMvnDepsFile = Task.dest / "compile-only-classpath.txt"
      os.write.over(
        compiledMvnDepsFile,
        resolvedCompileMvnDeps.map(_.path.toString()).mkString("\n")
      )
      Some(PathRef(compiledMvnDepsFile))
    } else
      None

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
   * @return
   */
  def androidReleaseSettings: T[AndroidBuildTypeSettings] = Task {
    AndroidBuildTypeSettings(
      isMinifyEnabled = true
    )
  }

  private def androidDefaultProguardFiles: Task[Seq[PathRef]] = Task.Anon {
    val dest = Task.dest
    androidDefaultProguardFileNames().map { fileName =>
      androidSdkModule().androidProguardPath() / fileName
    }.filter(os.exists).foreach { proguardFile =>
      os.copy(proguardFile, dest / proguardFile.last)
    }
    os.walk(dest).filter(os.isFile).map(PathRef(_))
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

    Task.log.debug("outputPath: " + outputPath)

    // Define diagnostic output file paths
    val mappingOut = destDir / "mapping.txt"
    val seedsOut = destDir / "seeds.txt"
    val usageOut = destDir / "usage.txt"
    val configOut = destDir / "configuration.txt"
    destDir / "missing_rules.txt"
    val baselineOutOpt = destDir / "baseline-profile-rewritten.txt"
    destDir / "res"

    // Extra ProGuard rules
    val extraRules =
      Seq(
        // Instruct R8 to print seeds and usage.
        s"-printseeds $seedsOut",
        s"-printusage $usageOut"
      ) ++
        (if (androidBuildSettings().isMinifyEnabled) then androidGeneratedMinifyKeepRules()
         else Seq())
    // Create an extra ProGuard config file
    val extraRulesFile = destDir / "extra-rules.pro"
    val extraRulesContent = extraRules.mkString("\n")
    os.write.over(extraRulesFile, extraRulesContent)

    val classpathClassFiles: Seq[PathRef] = androidPackagedClassfiles()
      .filter(_.path.ext == "class")

    val appCompiledFiles: Seq[PathRef] = androidPackagedCompiledClasses()
      .filter(_.path.ext == "class")

    val allClassFilesPathRefs =
      classpathClassFiles ++ appCompiledFiles ++ androidPackagedDeps()

    val allClassFiles = allClassFilesPathRefs.map(_.path.toString)
    val allClassFilesFile = Task.dest / "all-classes.txt"
    os.write.over(allClassFilesFile, allClassFiles.mkString("\n"))

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
      r8ArgsBuilder ++= Seq("--no-minification", "--no-tree-shaking")
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

    // ProGuard configuration files: add our extra rules file,
    // all provided config files and the common rules.
    val pgArgs =
      Seq(
        "--pg-conf",
        androidProguard().path.toString,
        "--pg-conf",
        extraRulesFile.toString
      ) ++ androidCommonProguardFiles().flatMap(pgf => Seq("--pg-conf", pgf.path.toString))

    r8ArgsBuilder ++= pgArgs

    val compileOnlyClasspath = androidR8CompileOnlyClasspath()

    r8ArgsBuilder ++= compileOnlyClasspath.toSeq.flatMap(compiledMvnDepsFile =>
      Seq(
        "--classpath",
        "@" + compiledMvnDepsFile.path.toString
      )
    )

    r8ArgsBuilder ++= androidR8Args()

    r8ArgsBuilder += "@" + allClassFilesFile.toString

    val r8Args = r8ArgsBuilder.result()

    (PathRef(outputPath), r8Args, allClassFilesPathRefs)
  }

  /**
   * Generates ProGuard/R8 keep rules to keep classes that are referenced in the AndroidManifest.xml
   * and in the layout XML files (for custom views).
   *
   * [[https://android.googlesource.com/platform/tools/base/+/refs/tags/studio-2025.1.3/sdk-common/src/main/java/com/android/ide/common/symbols/SymbolUtils.kt#235]]
   */
  def androidGeneratedMinifyKeepRules: T[Seq[String]] = Task {
    val keepClasses = extractKeepClassesFromManifest() ++ extractKeepClassesFromResources()
    keepClasses.map(c => s"-keep class $c { *; }")
  }

  private def combinePackageAndClassName(packageName: String, className: String): String = {
    className match {
      case c if c.startsWith(".") => s"$packageName$c"
      case c if !c.contains(".") => s"$packageName.$c"
      case c => c
    }
  }

  /**
   * Extracts the classes to keep from the Manifest file.
   *
   * See `mManifestData.mKeepClasses` in
   * [[https://android.googlesource.com/platform/tools/base/+/refs/tags/studio-2025.1.3/sdk-common/src/main/java/com/android/ide/common/xml/AndroidManifestParser.java]]
   */
  private def extractKeepClassesFromManifest: T[Seq[String]] = Task {
    val manifestPath: os.Path = androidMergedManifest().path
    val manifest = XML.loadFile(manifestPath.toIO)
    val packageName: String = (manifest \ "@package").text

    val androidNS = "http://schemas.android.com/apk/res/android"

    def collectClasses(label: String): Seq[String] = {
      (manifest \\ label).flatMap { node =>
        val className = node.attribute(androidNS, "name").map(_.text)
        className.map(c => combinePackageAndClassName(packageName, c))
      }
    }

    val nodes = Seq(
      "application",
      "activity",
      "service",
      "receiver",
      "provider",
      "instrumentation"
    )
    nodes.flatMap(collectClasses).distinct
  }

  private def extractKeepClassesFromResources: T[Seq[String]] = Task {
    val resDirs: Seq[os.Path] = androidResources().map(_.path)

    val layoutXmls: Seq[os.Path] = resDirs.flatMap { resDir =>
      if (os.exists(resDir) && os.isDir(resDir)) {
        os.list(resDir)
          .filter(p => os.isDir(p) && p.last.startsWith("layout"))
          .flatMap(layoutDir =>
            os.list(layoutDir)
              .filter(f => os.isFile(f) && f.ext == "xml")
          )
      } else Seq.empty[os.Path]
    }

    def collectClasses(node: Node): Seq[String] = {
      val tag = node.label
      val curr = if (tag.contains(".")) Seq(tag)
      else Seq.empty[String]
      curr ++ node.child.flatMap(collectClasses)
    }

    layoutXmls.flatMap { xmlFile =>
      val xml = XML.loadFile(xmlFile.toIO)
      collectClasses(xml)
    }
  }

}
