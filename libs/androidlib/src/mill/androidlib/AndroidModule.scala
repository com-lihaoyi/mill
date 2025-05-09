package mill.androidlib

import coursier.core.VariantSelector.VariantMatcher
import coursier.params.ResolutionParams
import coursier.{Dependency, Repository}
import mill.T
import mill.define.{ModuleRef, PathRef, Target, Task}
import mill.scalalib.*
import mill.util.Jvm

import scala.collection.immutable
import scala.xml.XML

trait AndroidModule extends JavaModule {

  private val rClassDirName = "RClass"
  private val compiledResourcesDirName = "compiled-resources"

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

  /**
   * Provides os.Path to an XML file containing configuration and metadata about your android library.
   */
  def androidManifest: Task[PathRef] = Task.Source("src/main/AndroidManifest.xml")

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
    Seq("--auto-add-overlay")
  }

  def androidTransitiveResources: T[Seq[PathRef]] = Task {
    T.traverse(transitiveModuleCompileModuleDeps) {
      case m: AndroidModule =>
        Task.Anon(m.androidResources())
      case _ =>
        Task.Anon(Seq.empty)
    }().flatten
  }

  def androidLibraryResources: T[Seq[PathRef]] = Task {
    androidUnpackArchives().flatMap(_.androidResources.toSeq)
  }

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  override def checkGradleModules: T[Boolean] = true
  override def resolutionParams: Task[ResolutionParams] = Task.Anon {
    super.resolutionParams().addVariantAttributes(
      "org.jetbrains.kotlin.platform.type" ->
        VariantMatcher.AnyOf(Seq(VariantMatcher.Equals("androidJvm"), VariantMatcher.Equals("jvm")))
    )
  }

  def androidOriginalCompileClasspath: T[Seq[PathRef]] = Task {
    super.compileClasspath()
  }

  /**
   * Replaces AAR files in classpath with their extracted JARs.
   */
  override def compileClasspath: T[Seq[PathRef]] = Task {
    // TODO process metadata shipped with Android libs. It can have some rules with Target SDK, for example.
    // TODO support baseline profiles shipped with Android libs.
    (androidOriginalCompileClasspath().filter(_.path.ext != "aar") ++ androidResolvedMvnDeps()).map(
      _.path
    ).distinct.map(PathRef(_))
  }

  override def localRunClasspath: T[Seq[PathRef]] =
    super.localRunClasspath() :+ androidProcessedResources()

  /**
   * Combines module resources with those unpacked from AARs.
   */
  def androidResources: T[Seq[PathRef]] = Task.Sources {
    moduleDir / "src/main/res"
  }

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

  def androidTransformAarFiles(resolvedDeps: Task[Seq[PathRef]]): Task[Seq[UnpackedDep]] = Task {
    val transformDest = Task.dest / "transform"
    val aarFiles = resolvedDeps()
      .map(_.path)
      .filter(_.ext == "aar")
      .distinct

    // TODO do it in some shared location, otherwise each module is doing the same, having its own copy for nothing
    extractAarFiles(aarFiles, transformDest)
  }

  override def mapDependencies: Task[Dependency => Dependency] = Task.Anon {
    super.mapDependencies().andThen(dep => dep.withEndorseStrictVersions(true))
  }

  /**
   * Classpath for the manifest merger run.
   */
  def manifestMergerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        mvn"com.android.tools.build:manifest-merger:${androidSdkModule().manifestMergerVersion()}"
      )
    )
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

  final def extractAarFiles(aarFiles: Seq[os.Path], taskDest: os.Path): Seq[UnpackedDep] = {
    aarFiles.map(aarFile => {
      val extractDir = taskDest / aarFile.baseName
      os.unzip(aarFile, extractDir)
      val name = aarFile.baseName

      def pathOption(p: os.Path): Option[PathRef] = if (os.exists(p)) {
        Some(PathRef(p))
      } else None

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
      UnpackedDep(
        name,
        classesJar,
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

  /**
   * Creates a merged manifest from application and dependencies manifests.
   *
   * See [[https://developer.android.com/build/manage-manifests]] for more details.
   */
  def androidMergedManifest: T[PathRef] = Task {
    val libManifests = androidUnpackArchives().map(_.manifest.get)
    val mergedManifestPath = T.dest / "AndroidManifest.xml"
    // TODO put it to the dedicated worker if cost of classloading is too high
    Jvm.callProcess(
      mainClass = "com.android.manifmerger.Merger",
      mainArgs = Seq(
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
        s"version_name=${androidVersionName()}",
        "--out",
        mergedManifestPath.toString()
      ) ++ libManifests.flatMap(m => Seq("--libs", m.path.toString())),
      classPath = manifestMergerClasspath().map(_.path)
    )
    PathRef(mergedManifestPath)
  }

  def androidLibsRClasses: T[Seq[PathRef]] = Task {
    // TODO do this better
    // So we have application R.java class generated by aapt2 link, which includes IDs of app resources + libs resources.
    // But we also need to have R.java classes for libraries. The process below is quite hacky and inefficient, because:
    // * it will generate R.java for the library even library has no resources declared
    // * R.java will have not only resource ID from this library, but from other libraries as well. They should be stripped.
    val rClassDir = androidCompiledResources()._1.path / rClassDirName
    val mainRClassPath = os.walk(rClassDir)
      .find(_.last == "R.java")
      .get

    val mainRClass = os.read(mainRClassPath)
    val libsPackages = androidUnpackArchives()
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

  /** In which package to place the generated R sources */
  protected def androidGeneratedResourcesPackage: String

  /**
   * Compiles Android resources and generates `R.java` and `res.apk`.
   *
   * @return [[(PathRef, Seq[PathRef]]] to the directory with application's `R.java` and `res.apk` and a list
   *         of the zip files containing the resource compiled files
   *
   *         For more details on the aapt2 tool, refer to:
   *         [[https://developer.android.com/tools/aapt2 aapt Documentation]]
   */
  def androidCompiledResources: T[(PathRef, Seq[PathRef])] = Task {
    val rClassDir = T.dest / rClassDirName
    val compiledResDir = T.dest / compiledResourcesDirName
    os.makeDir(compiledResDir)
    val compiledResources = collection.mutable.Buffer[os.Path]()

    val transitiveResources = androidTransitiveResources().map(_.path).filter(os.exists)

    val localResources =
      androidResources().map(_.path).filter(os.exists) ++ androidLibraryResources().map(_.path)

    val allResources = localResources ++ transitiveResources

    for (resDir <- allResources) {
      val segmentsSeq = resDir.segments.toSeq
      val libraryName = segmentsSeq.dropRight(1).last
      // if not directory, but file, it should have one of the possible extensions: .flata, .zip, .jar, .jack,
      // because aapt2 link command relies on the extension to check if compile output is archive
      // see https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/tools/aapt2/cmd/Link.cpp;l=1565-1567;drc=7b647e4ea0e92f33c19b315eaed364ee067ba0aa
      val compiledLibResPath = compiledResDir / s"$libraryName.zip"
      compiledResources += compiledLibResPath

      val libCompileArgsBuilder = Seq.newBuilder[String]
      libCompileArgsBuilder ++= Seq(androidSdkModule().aapt2Path().path.toString(), "compile")
      if (T.log.debugEnabled) {
        libCompileArgsBuilder += "-v"
      }
      libCompileArgsBuilder ++= Seq(
        // TODO: move away from --dir usage, because it will compile once again all resources, even the ones
        //  which didn't change. See comment here https://developer.android.com/tools/aapt2#compile_options
        "--dir",
        resDir.toString(),
        "-o",
        // if path is not a directory, then it will be treated as ZIP archive where all the
        // compiled resources will be packed
        // pros of zip vs directory: [potentially] less I/O syscalls to write output, cons: CPU usage, time to decode
        compiledLibResPath.toString()
      )
      if (androidIsDebug()) {
        libCompileArgsBuilder += "--no-crunch"
      }

      val libCompileArgs = libCompileArgsBuilder.result()
      T.log.info(
        s"Compiling resources of $libraryName with the following command: ${libCompileArgs.mkString(" ")}"
      )
      os.call(libCompileArgs)
    }

    // TODO support asserts folder and other secondary options
    // TODO support stable IDs
    val appLinkArgsBuilder = Seq.newBuilder[String]
    appLinkArgsBuilder ++= Seq(androidSdkModule().aapt2Path().path.toString, "link")
    if (T.log.debugEnabled) {
      appLinkArgsBuilder += "-v"
    }
    appLinkArgsBuilder ++= Seq(
      "-I",
      androidSdkModule().androidJarPath().path.toString,
      "--manifest",
      androidMergedManifest().path.toString,
      "--custom-package",
      androidGeneratedResourcesPackage,
      "--java",
      rClassDir.toString,
      "--min-sdk-version",
      androidMinSdk().toString,
      "--target-sdk-version",
      androidTargetSdk().toString,
      "--version-code",
      androidVersionCode().toString,
      "--version-name",
      androidVersionName(),
      "--proguard-main-dex",
      (T.dest / "main-dex-rules.pro").toString,
      "--proguard-conditional-keep-rules",
      "-o",
      (T.dest / "res.apk").toString
    )

    if (!androidIsDebug()) {
      appLinkArgsBuilder += "--proguard-minimal-keep-rules"
    }
    if (androidIsDebug()) {
      appLinkArgsBuilder += "--debug-mode"
    }
    appLinkArgsBuilder ++= androidAaptOptions()
    appLinkArgsBuilder ++= compiledResources.map(_.toString())

    val appLinkArgs = appLinkArgsBuilder.result()

    T.log.info(
      s"Linking application resources with the command: ${appLinkArgs.mkString(" ")}"
    )

    os.call(appLinkArgs)

    (PathRef(T.dest), compiledResources.toSeq.map(PathRef(_)))
  }

  /**
   * Creates an intermediate R.jar that includes all the resources from the application and its dependencies.
   */
  def androidProcessedResources: Target[PathRef] = Task {

    val sources = androidLibsRClasses()

    val rJar = Task.dest / "R.jar"

    val classesDest = jvmWorker()
      .worker()
      .compileJava(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = sources.map(_.path),
        compileClasspath = Seq.empty,
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
    os.walk(compile().classes.path)
      .filter(_.ext == "class")
      .map(PathRef(_))
  }

  def androidPackagedDeps: T[Seq[PathRef]] = Task {
    compileClasspath()
      .filter(_ != androidSdkModule().androidJarPath())
      .filter(_.path.ext == "jar")
  }

  /** Additional library classes provided */
  def libraryClassesPaths: T[Seq[PathRef]] = Task {
    androidSdkModule().androidLibsClasspaths()
  }

  /**
   * Specifies the path to the main-dex rules file, which contains ProGuard rules
   * for determining which classes should be included in the primary DEX file.
   */
  def mainDexRules: T[Option[PathRef]] = Task {
    Some(PathRef(androidCompiledResources()._1.path / "main-dex-rules.pro"))
  }

  /**
   * Returns the path to the main-dex list file, which explicitly lists the classes
   * to be included in the primary DEX file. Currently, this is not defined.
   */
  def mainDexList: T[Option[PathRef]] = Task {
    None
  }

  /** Optional baseline profile for ART rewriting */
  def baselineProfile: T[Option[PathRef]] = Task {
    None
  }

}
