package mill.androidlib

import coursier.Repository
import coursier.core.VariantSelector.VariantMatcher
import coursier.params.ResolutionParams
import mill.T
import mill.androidlib.manifestmerger.AndroidManifestMerger
import mill.api.{ModuleRef, PathRef, Task}
import mill.scalalib.*
import mill.api.JsonFormatters.given
import mill.javalib.api.CompilationResult
import os.Path

import scala.collection.immutable
import scala.xml.*

trait AndroidModule extends JavaModule {

  // https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/tasks/D8BundleMainDexListTask.kt;l=210-223;drc=66ab6bccb85ce3ed7b371535929a69f494d807f0
  val mainDexPlatformRules = Seq(
    "-keep public class * extends android.app.Instrumentation {\n" +
      "  <init>(); \n" +
      "  void onCreate(...);\n" +
      "  android.app.Application newApplication(...);\n" +
      "  void callApplicationOnCreate(android.app.Application);\n" +
      "}",
    "-keep public class * extends android.app.Application { " +
      "  <init>();\n" +
      "  void attachBaseContext(android.content.Context);\n" +
      "}",
    "-keep public class * extends android.app.backup.BackupAgent { <init>(); }",
    "-keep public class * extends android.test.InstrumentationTestCase { <init>(); }"
  )

  /**
   * Adds "aar" to the handled artifact types.
   */
  override def artifactTypes: T[Set[coursier.Type]] =
    Task {
      super.artifactTypes() + coursier.Type("aar")
    }

  override def sources: T[Seq[PathRef]] = Task.Sources("src/main/java")

  /**
   * Provides access to the Android SDK configuration.
   */
  def androidSdkModule: ModuleRef[AndroidSdkModule]

  def androidManifestLocation: T[PathRef] = Task.Source("src/main/AndroidManifest.xml")

  /**
   * Provides os.Path to an XML file containing configuration and metadata about your android application.
   * TODO dynamically add android:debuggable
   */
  def androidManifest: T[PathRef] = Task {
    val manifestFromSourcePath = androidManifestLocation().path

    val manifestElem = XML.loadFile(manifestFromSourcePath.toString()) %
      Attribute(None, "xmlns:android", Text("http://schemas.android.com/apk/res/android"), Null)
    // add the application package
    val manifestWithPackage =
      manifestElem % Attribute(None, "package", Text(androidNamespace), Null)

    val generatedManifestPath = Task.dest / "AndroidManifest.xml"
    os.write(generatedManifestPath, manifestWithPackage.mkString)

    PathRef(generatedManifestPath)
  }

  /**
   * Controls debug vs release build type. Default is `true`, meaning debug build will be generated.
   *
   * This option will probably go away in the future once build variants are supported.
   */
  def androidIsDebug: T[Boolean] = {
    true
  }

  /**
   * The minimum SDK version to use. Default is 1.
   *
   * See [[https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#min]] for more details.
   */
  def androidMinSdk: T[Int] = 1

  /**
   * This setting defines which Android API level your project compiles against. This is a required property for
   * Android builds.
   *
   * It determines what Android APIs are available during compilation - your code can only use APIs from this level
   * or lower.
   *
   * See [[https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#ApiLevels]] for more details.
   */
  def androidCompileSdk: T[Int]

  /**
   * The target SDK version to use. Default is equal to the [[androidCompileSdk]] value.
   *
   * See [[https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#target]] for more details.
   */
  def androidTargetSdk: T[Int] = androidCompileSdk

  /**
   * The version name of the application. Default is "1.0".
   *
   * See [[https://developer.android.com/studio/publish/versioning]] for more details.
   */
  def androidVersionName: T[String] = "1.0"

  /**
   * Version code of the application. Default is 1.
   *
   * See [[https://developer.android.com/studio/publish/versioning]] for more details.
   */
  def androidVersionCode: T[Int] = 1

  /**
   * Specifies AAPT options for Android resource compilation.
   */
  def androidAaptOptions: T[Seq[String]] = Task {
    if (androidIsDebug()) {
      Seq(
        "--proguard-minimal-keep-rules",
        "--debug-mode",
        "--auto-add-overlay"
      )
    } else {
      Seq("--auto-add-overlay")
    }
  }

  /**
   * Gets all the android resources (typically in res/ directory)
   * from the [[transitiveModuleCompileModuleDeps]]
   * @return
   */
  def androidTransitiveResources: T[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleCompileModuleDeps) {
      case m: AndroidModule =>
        Task.Anon(m.androidResources())
      case _ =>
        Task.Anon(Seq.empty)
    }().flatten
  }

  /**
   * Gets all the android resources (typically in res/ directory)
   * from the library dependencies using [[androidUnpackArchives]]
   * @return
   */
  def androidLibraryResources: T[Seq[PathRef]] = Task {
    androidUnpackRunArchives().flatMap(_.androidResources.toSeq)
  }

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  override def checkGradleModules: T[Boolean] = true
  override def resolutionParams: Task[ResolutionParams] = Task.Anon {
    super.resolutionParams().addVariantAttributes(
      "org.jetbrains.kotlin.platform.type" ->
        VariantMatcher.AnyOf(Seq(
          VariantMatcher.Equals("androidJvm"),
          VariantMatcher.Equals("common"),
          VariantMatcher.Equals("jvm")
        )),
      "org.gradle.category" -> VariantMatcher.Library,
      "org.gradle.jvm.environment" ->
        VariantMatcher.AnyOf(Seq(
          VariantMatcher.Equals("android"),
          VariantMatcher.Equals("common"),
          VariantMatcher.Equals("standard-jvm")
        ))
    )
  }

  /**
   * The original compiled classpath (containing a mix of jars and aars).
   * @return
   */
  def androidOriginalCompileClasspath: T[Seq[PathRef]] = Task {
    super.compileClasspath()
  }

  /**
   * Replaces AAR files in [[androidOriginalCompileClasspath]] with their extracted JARs.
   */
  override def compileClasspath: T[Seq[PathRef]] = Task {
    // TODO process metadata shipped with Android libs. It can have some rules with Target SDK, for example.
    // TODO support baseline profiles shipped with Android libs.
    (androidOriginalCompileClasspath().filter(_.path.ext != "aar") ++ androidResolvedMvnDeps()).map(
      _.path
    ).distinct.map(PathRef(_)) ++ androidTransitiveLibRClasspath()
  }

  /**
   * Android res folder
   */
  def androidResources: T[Seq[PathRef]] = Task.Sources {
    moduleDir / "src/main/res"
  }

  /**
   * Constructs the run classpath by extracting JARs from AAR files where
   * applicable using [[androidResolvedRunMvnDeps]]
   * @return
   */
  override def runClasspath: T[Seq[PathRef]] = Task {
    (super.runClasspath().filter(_.path.ext != "aar") ++ androidResolvedRunMvnDeps()).map(
      _.path
    ).distinct.map(PathRef(_))
  }

  /**
   * Resolves run mvn deps using [[resolvedRunMvnDeps]] and transforms
   * any aar files to jars
   * @return
   */
  def androidResolvedRunMvnDeps: T[Seq[PathRef]] = Task {
    transformedAndroidDeps(Task.Anon(resolvedRunMvnDeps()))()
  }

  /**
   * Resolves mvn deps using [[resolvedMvnDeps]] and transforms
   * any aar files to jars
   *
   * @return
   */
  def androidResolvedMvnDeps: T[Seq[PathRef]] = Task {
    transformedAndroidDeps(Task.Anon(resolvedMvnDeps()))()
  }

  protected def transformedAndroidDeps(resolvedDeps: Task[Seq[PathRef]]): Task[Seq[PathRef]] =
    Task.Anon {
      val transformedAarFilesToJar: Seq[PathRef] =
        androidTransformAarFiles(Task.Anon(resolvedDeps()))()
          .flatMap(_.classesJar)
      val jarFiles = resolvedDeps()
        .filter(_.path.ext == "jar")
        .distinct
      transformedAarFilesToJar ++ jarFiles
    }

  def androidTransformAarFiles(resolvedDeps: Task[Seq[PathRef]]): Task[Seq[UnpackedDep]] =
    Task.Anon {
      val transformDest = Task.dest / "transform"
      val aarFiles = resolvedDeps()
        .map(_.path)
        .filter(_.ext == "aar")
        .distinct

      extractAarFiles(aarFiles, transformDest)
    }

  /**
   * Runtime deps collected from repackaged content
   * from (usually) AAR files. Can usually be found in
   * libs/repackaged.jar.
   * @return
   */
  def androidRepackagedDeps: T[Seq[PathRef]] = Task {
    androidTransformAarFiles(Task.Anon(resolvedRunMvnDeps()))()
      .flatMap(_.repackagedJars)
  }

  /**
   * Extracts JAR files and resources from AAR dependencies.
   */
  def androidUnpackArchives: T[Seq[UnpackedDep]] = Task {
    // The way Android is handling configurations for dependencies is a bit different from canonical Maven: it has
    // `api` and `implementation` configurations. If dependency is `api` dependency, it will be exposed to consumers
    // of the library, but if it is `implementation`, then it won't. The simplest analogy is api = compile,
    // implementation = runtime, but both are actually used for compilation and packaging of the final DEX.
    // More here https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_separation.
    //
    // In Gradle terms using only `resolvedRunMvnDeps` won't be complete, because source modules can be also
    // api/implementation, but Mill has no such configurations.
    val aarFiles = androidOriginalCompileClasspath()
      .map(_.path)
      .filter(_.ext == "aar")
      .distinct

    // TODO do it in some shared location, otherwise each module is doing the same, having its own copy for nothing
    extractAarFiles(aarFiles, Task.dest)
  }

  def androidUnpackRunArchives: T[Seq[UnpackedDep]] = Task {
    val aarFiles = resolvedRunMvnDeps()
      .map(_.path)
      .filter(_.ext == "aar")
      .distinct

    // TODO do it in some shared location, otherwise each module is doing the same, having its own copy for nothing
    extractAarFiles(aarFiles, Task.dest)
  }

  final def extractAarFiles(aarFiles: Seq[os.Path], taskDest: os.Path): Seq[UnpackedDep] = {
    aarFiles.map(aarFile => {
      val extractDir = taskDest / aarFile.baseName
      os.unzip(aarFile, extractDir)
      val name = aarFile.baseName

      def pathOption(p: os.Path): Option[PathRef] = if (os.exists(p)) {
        Some(PathRef(p))
      } else None

      def listFiles(p: os.Path, ext: String): Seq[PathRef] = if (os.exists(p)) {
        os.walk(p).filter(_.ext == ext).map(PathRef(_))
      } else Seq.empty[PathRef]

      val classesJar = pathOption(extractDir / "classes.jar")
      val proguardRules = pathOption(extractDir / "proguard.txt")
      val androidResources = pathOption(extractDir / "res")
      val manifest = pathOption(extractDir / "AndroidManifest.xml")
      val lintJar = pathOption(extractDir / "lint.jar")
      val metaInf = pathOption(extractDir / "META-INF")
      val nativeLibs = pathOption(extractDir / "jni")
      val baselineProfile = pathOption(extractDir / "baseline-prof.txt")
      val stableIdsRFile = pathOption(extractDir / "R.txt")
      val publicResFile = pathOption(extractDir / "public.txt")
      val repackaged = listFiles(extractDir / "libs", "jar")

      UnpackedDep(
        name,
        classesJar,
        repackaged,
        proguardRules,
        androidResources,
        manifest,
        lintJar,
        metaInf,
        nativeLibs,
        baselineProfile,
        stableIdsRFile,
        publicResFile
      )
    })
  }

  def androidManifestMergerModuleRef: ModuleRef[AndroidManifestMerger] =
    ModuleRef(AndroidManifestMerger)

  def androidMergeableManifests: Task[Seq[PathRef]] = Task {
    androidUnpackArchives().flatMap(_.manifest)
  }

  def androidMergedManifestArgs: Task[Seq[String]] = Task {
    Seq(
      "--main",
      androidManifest().path.toString(),
      "--remove-tools-declarations",
      "--property",
      s"min_sdk_version=${androidMinSdk()}",
      "--property",
      s"target_sdk_version=${androidTargetSdk()}",
      "--property",
      s"version_code=${androidVersionCode()}",
      "--property",
      s"version_name=${androidVersionName()}"
    ) ++ androidMergeableManifests().flatMap(m => Seq("--libs", m.path.toString()))
  }

  /**
   * Creates a merged manifest from application and dependencies manifests using.
   * Merged manifests are given via [[androidMergeableManifests]] and merger args via
   * [[androidMergedManifestArgs]]
   *
   * See [[https://developer.android.com/build/manage-manifests]] for more details.
   */
  def androidMergedManifest: T[PathRef] = Task {

    val mergedAndroidManifestLocation = androidManifestMergerModuleRef().androidMergedManifest(
      args = Task.Anon(androidMergedManifestArgs())
    )()

    val mergedAndroidManifestDest = Task.dest / "AndroidManifest.xml"

    os.move(mergedAndroidManifestLocation, mergedAndroidManifestDest)

    PathRef(mergedAndroidManifestDest)
  }

  def androidLibsRClasses: T[Seq[PathRef]] = Task {
    // TODO do this better
    // So we have application R.java class generated by aapt2 link, which includes IDs of app resources + libs resources.
    // But we also need to have R.java classes for libraries. The process below is quite hacky and inefficient, because:
    // * it will generate R.java for the library even library has no resources declared
    // * R.java will have not only resource ID from this library, but from other libraries as well. They should be stripped.
    val rClassDir = androidLinkedResources().path / "generatedSources/java"
    val mainRClassPath = os.walk(rClassDir)
      .find(_.last == "R.java")
      .get

    val mainRClass = os.read(mainRClassPath)
    val libsPackages = androidUnpackRunArchives()
      .flatMap(_.manifest)
      .map(_.path)
      .map(path => ((XML.loadFile(path.toIO) \\ "manifest").head \ "@package").head.toString())
      .distinct

    val libClasses: Seq[PathRef] = for {
      libPackage <- libsPackages
      libRClassPath = Task.dest / libPackage.split('.') / "R.java"
      _ = os.write(
        libRClassPath,
        mainRClass.replaceAll("package .+;", s"package $libPackage;"),
        createFolders = true
      )
    } yield PathRef(libRClassPath)

    libClasses :+ PathRef(mainRClassPath)
  }

  /**
   * The Java compiled classes of [[androidResources]]
   */
  def androidCompiledRClasses: T[CompilationResult] = Task(persistent = true) {
    jvmWorker()
      .worker()
      .compileJava(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = androidLibsRClasses().map(_.path),
        compileClasspath = Seq.empty,
        javaHome = javaHome().map(_.path),
        javacOptions = javacOptions() ++ mandatoryJavacOptions(),
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation()
      )
  }

  def androidLibRClasspath: T[Seq[PathRef]] = Task {
    Seq(androidCompiledRClasses().classes)
  }

  def androidTransitiveLibRClasspath: T[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleDeps) {
      case m: AndroidModule =>
        Task.Anon(m.androidLibRClasspath())
      case _ =>
        Task.Anon(Seq.empty[PathRef])
    }().flatten
  }

  /**
   * Namespace of the Android module.
   * Used in manifest package and also used as the package to place the generated R sources
   */
  def androidNamespace: String

  /**
   * Gets the extracted android resources from the dependencies using [[androidLibraryResources]]
   * and compiles them into flata files using aapt2. This allows for the resources to be linked
   * using overlay.
   * @return
   */
  def androidCompiledLibResources: T[PathRef] = Task {
    val libAndroidResources: Seq[Path] = androidLibraryResources().map(_.path)

    val aapt2Compile = Seq(androidSdkModule().aapt2Path().path.toString(), "compile")

    for (libResDir <- libAndroidResources) {
      val segmentsSeq = libResDir.segments.toSeq
      val libraryName = segmentsSeq.dropRight(1).last
      val dirDest = Task.dest / libraryName
      os.makeDir(dirDest)
      val aapt2Args = Seq(
        "--dir",
        libResDir.toString,
        "-o",
        dirDest.toString
      )

      os.call(aapt2Compile ++ aapt2Args)
    }

    PathRef(Task.dest)
  }

  /**
   * Gets all the android resources from this module and its
   * module dependencies and compiles them into flata files.
   * @return
   */
  def androidCompiledModuleResources = Task {

    val moduleResources =
      androidResources().map(_.path).filter(os.exists) ++
        androidTransitiveResources().map(_.path).filter(os.exists)

    val aapt2Compile = Seq(androidSdkModule().aapt2Path().path.toString(), "compile")

    for (libResDir <- moduleResources) {
      val segmentsSeq = libResDir.segments.toSeq
      val libraryName = segmentsSeq.dropRight(1).last
      val dirDest = Task.dest / libraryName
      os.makeDir(dirDest)
      val aapt2Args = Seq(
        "--dir",
        libResDir.toString,
        "-o",
        dirDest.toString
      )

      os.call(aapt2Compile ++ aapt2Args)
    }

    PathRef(Task.dest)

  }

  /**
   * Links all the resources coming from [[androidCompiledLibResources]] and
   * [[androidCompiledModuleResources]] using auto overlay to resolve conflicts.
   * For more information see [[https://developer.android.com/tools/aapt2#link]]
   * @return a directory which contains the apk, proguard and generated R sources.
   */
  def androidLinkedResources: T[PathRef] = Task {
    val compiledLibResDir = androidCompiledLibResources().path
    val moduleResDir = androidCompiledModuleResources().path

    val filesToLink = os.walk(compiledLibResDir).filter(os.isFile(_)) ++
      os.walk(moduleResDir).filter(os.isFile(_))

    val javaRClassDir = Task.dest / "generatedSources/java"
    val apkDir = Task.dest / "apk"
    val proguard = Task.dest / "proguard"

    os.makeDir.all(javaRClassDir)
    os.makeDir.all(proguard)
    os.makeDir(apkDir)

    val resApkFile = apkDir / "res.apk"

    val mainDexRulesProFile = proguard / "main-dex-rules.pro"

    val aapt2Link = Seq(androidSdkModule().aapt2Path().path.toString(), "link")

    val linkArgs = Seq(
      "-I",
      androidSdkModule().androidJarPath().path.toString,
      "--manifest",
      androidMergedManifest().path.toString,
      "--custom-package",
      androidNamespace,
      "--java",
      javaRClassDir.toString,
      "--min-sdk-version",
      androidMinSdk().toString,
      "--target-sdk-version",
      androidTargetSdk().toString,
      "--version-code",
      androidVersionCode().toString,
      "--version-name",
      androidVersionName(),
      "--proguard-main-dex",
      mainDexRulesProFile.toString,
      "--proguard-conditional-keep-rules"
    ) ++ androidAaptOptions() ++ Seq(
      "-o",
      resApkFile.toString
    ) ++ filesToLink.flatMap(flat => Seq("-R", flat.toString()))

    Task.log.info((aapt2Link ++ linkArgs).mkString(" "))

    os.call(aapt2Link ++ linkArgs)

    PathRef(Task.dest)
  }

  /**
   * Creates an intermediate R.jar that includes all the resources from the application and its dependencies.
   */
  def androidProcessedResources: T[PathRef] = Task {

    val sources = androidLibsRClasses()

    val rJar = Task.dest / "R.jar"

    val classesDest = jvmWorker()
      .worker()
      .compileJava(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = sources.map(_.path),
        compileClasspath = Seq.empty,
        javaHome = javaHome().map(_.path),
        javacOptions = javacOptions() ++ mandatoryJavacOptions(),
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation()
      ).get.classes.path

    os.zip(rJar, Seq(classesDest))

    PathRef(rJar)
  }

  /** All individual classfiles inherited from the classpath that will be included into the dex */
  def androidPackagedClassfiles: T[Seq[PathRef]] = Task {
    compileClasspath()
      .map(_.path).filter(os.isDir)
      .flatMap(os.walk(_))
      .filter(os.isFile)
      .filter(_.ext == "class")
      .map(PathRef(_))
  }

  def androidPackagedCompiledClasses: T[Seq[PathRef]] = Task {
    Seq(compile().classes.path)
      .filter(os.exists)
      .flatMap(os.walk(_))
      .filter(_.ext == "class")
      .map(PathRef(_))
  }

  def androidPackagedDeps: T[Seq[PathRef]] = Task {
    (androidResolvedRunMvnDeps() ++ androidRepackagedDeps())
      .filter(_ != androidSdkModule().androidJarPath())
      .filter(_.path.ext == "jar")
  }

  /** Additional library classes provided */
  def libraryClassesPaths: T[Seq[PathRef]] = Task {
    androidSdkModule().androidLibsClasspaths()
  }

  /** Optional baseline profile for ART rewriting */
  def baselineProfile: T[Option[PathRef]] = Task {
    None
  }

}
