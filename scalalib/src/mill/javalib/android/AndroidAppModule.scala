package mill.javalib.android

import coursier.Repository
import mill._
import mill.scalalib._
import mill.api.{Logger, PathRef, internal}
import mill.define.{ModuleRef, Task}
import mill.scalalib.bsp.BspBuildTarget
import mill.testrunner.TestResult
import mill.util.Jvm
import os.RelPath
import upickle.default._

import scala.jdk.OptionConverters.RichOptional
import scala.xml.XML

/**
 * Enumeration for Android Lint report formats, providing predefined formats
 * with corresponding flags and file extensions. Includes utility methods
 * for implicit conversions, serialization, and retrieving all supported formats.
 */
object AndroidLintReportFormat extends Enumeration {
  protected case class Format(flag: String, extension: String) extends super.Val {
    override def toString: String = extension
  }

  implicit def valueToFormat(v: Value): Format = v.asInstanceOf[Format]

  val Html: Format = Format("--html", "html")
  val Xml: Format = Format("--xml", "xml")
  val Txt: Format = Format("--text", "txt")
  val Sarif: Format = Format("--sarif", "sarif")

  // Define an implicit ReadWriter for the Format case class
  implicit val formatRW: ReadWriter[Format] = macroRW

  // Optional: Add a method to retrieve all possible values
  val allFormats: List[Format] = List(Html, Xml, Txt, Sarif)
}

/**
 * Trait for building Android applications using the Mill build tool.
 *
 * This trait defines all the necessary steps for building an Android app from Java sources,
 * integrating both Android-specific tasks and generic Java tasks by extending the
 * [[AndroidSdkModule]] (for Android SDK interactions) and [[JavaModule]] (for standard Java tasks).
 *
 * It provides a structured way to handle various steps in the Android app build process,
 * including compiling Java sources, creating DEX files, generating resources, packaging
 * APKs, optimizing, and signing APKs.
 *
 * [[https://developer.android.com/studio Android Studio Documentation]]
 */
@mill.api.experimental
trait AndroidAppModule extends JavaModule {

  private val parent: AndroidAppModule = this

  // https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/tasks/D8BundleMainDexListTask.kt;l=210-223;drc=66ab6bccb85ce3ed7b371535929a69f494d807f0
  private val mainDexPlatformRules = Seq(
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

  private val compiledResourcesDirName = "compiled-resources"
  private val rClassDirName = "RClass"

  protected val debugKeyStorePass = "mill-android"
  protected val debugKeyAlias = "mill-android"
  protected val debugKeyPass = "mill-android"

  /**
   * Adds "aar" to the handled artifact types.
   */
  override def artifactTypes: T[Set[coursier.Type]] =
    Task { super.artifactTypes() + coursier.Type("aar") }

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
  }

  override def sources: T[Seq[PathRef]] = Task.Sources(millSourcePath / "src/main/java")

  /**
   * Provides access to the Android SDK configuration.
   */
  def androidSdkModule: ModuleRef[AndroidSdkModule]

  /**
   * Provides os.Path to an XML file containing configuration and metadata about your android application.
   */
  def androidManifest: Task[PathRef] = Task.Source(millSourcePath / "src/main/AndroidManifest.xml")

  /**
   * Name of the release keystore file. Default is not set.
   */
  def androidReleaseKeyName: T[Option[String]] = Task { None }

  // TODO alias, keystore pass and pass below are sensitive credentials and shouldn't be leaked to disk/console.
  // In the current state they are leaked, because Task dumps output to the json.
  // Should be fixed ASAP.

  /**
   * Name of the key alias in the release keystore. Default is not set.
   */
  def androidReleaseKeyAlias: T[Option[String]] = Task { None }

  /**
   * Password for the release key. Default is not set.
   */
  def androidReleaseKeyPass: T[Option[String]] = Task { None }

  /**
   * Password for the release keystore. Default is not set.
   */
  def androidReleaseKeyStorePass: T[Option[String]] = Task { None }

  /**
   * Default os.Path to the keystore file, derived from `androidReleaseKeyName()`.
   * Users can customize the keystore file name to change this path.
   */
  def androidReleaseKeyPath: T[Option[PathRef]] = Task {
    androidReleaseKeyName().map(name => PathRef(millSourcePath / name))
  }

  /**
   * Specifies the file format(s) of the lint report. Available file formats are defined in AndroidLintReportFormat,
   * such as [[AndroidLintReportFormat.Html]], [[AndroidLintReportFormat.Xml]], [[AndroidLintReportFormat.Txt]],
   * and [[AndroidLintReportFormat.Sarif]].
   */
  def androidLintReportFormat: T[Seq[AndroidLintReportFormat.Value]] =
    Task { Seq(AndroidLintReportFormat.Html) }

  /**
   * Specifies the lint configuration XML file path. This allows setting custom lint rules or modifying existing ones.
   */
  def androidLintConfigPath: T[Option[PathRef]] = Task { None }

  /**
   * Specifies the lint baseline XML file path. This allows using a baseline to suppress known lint warnings.
   */
  def androidLintBaselinePath: T[Option[PathRef]] = Task { None }

  /**
   * Determines whether the build should fail if Android Lint detects any issues.
   */
  def androidLintAbortOnError: Boolean = true

  /**
   * Specifies additional arguments for the Android Lint tool.
   * Allows for complete customization of the lint command.
   */
  def androidLintArgs: T[Seq[String]] = Task { Seq.empty[String] }

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
   * The minimum SDK version to use. Default is 1.
   *
   * See [[https://developer.android.com/guide/topics/manifest/uses-sdk-element.html#min]] for more details.
   */
  def androidMinSdk: T[Int] = 1

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
   * Controls debug vs release build type. Default is `true`, meaning debug build will be generated.
   *
   * This option will probably go away in the future once build variants are supported.
   */
  def androidIsDebug: T[Boolean] = true

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
    // In Gradle terms using only `resolvedRunIvyDeps` won't be complete, because source modules can be also
    // api/implementation, but Mill has no such configurations.
    val aarFiles = (super.compileClasspath() ++ super.resolvedRunIvyDeps())
      .map(_.path)
      .filter(_.ext == "aar")
    var result: Seq[UnpackedDep] = Seq()

    // TODO do it in some shared location, otherwise each module is doing the same, having its own copy for nothing
    for (aarFile <- aarFiles) {
      val extractDir = Task.dest / aarFile.baseName
      os.unzip(aarFile, extractDir)
      val name = aarFile.baseName

      def pathOption(p: os.Path): Option[PathRef] = if (os.exists(p)) {
        Some(PathRef(p))
      } else None

      val classesJar = pathOption(extractDir / "classes.jar")
      val proguardRules = pathOption(extractDir / "proguard.txt")
      val resources = pathOption(extractDir / "res")
      val manifest = pathOption(extractDir / "AndroidManifest.xml")
      val lintJar = pathOption(extractDir / "lint.jar")
      val metaInf = pathOption(extractDir / "META-INF")
      val nativeLibs = pathOption(extractDir / "jni")
      val baselineProfile = pathOption(extractDir / "baseline-prof.txt")
      val stableIdsRFile = pathOption(extractDir / "R.txt")
      val publicResFile = pathOption(extractDir / "public.txt")
      result +:= UnpackedDep(
        name,
        classesJar,
        proguardRules,
        resources,
        manifest,
        lintJar,
        metaInf,
        nativeLibs,
        baselineProfile,
        stableIdsRFile,
        publicResFile
      )
    }
    result
  }

  /**
   * Combines module resources with those unpacked from AARs.
   */
  override def resources: T[Seq[PathRef]] = Task {
    val libResFolders = androidUnpackArchives().flatMap(_.resources)
    libResFolders :+ PathRef(millSourcePath / "src/main/res")
  }

  @internal
  override def bspCompileClasspath: T[Agg[UnresolvedPath]] = Task {
    compileClasspath().map(_.path).map(UnresolvedPath.ResolvedPath(_))
  }

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    baseDirectory = Some(millSourcePath / "src/main"),
    tags = Seq("application")
  )

  /**
   * Replaces AAR files in classpath with their extracted JARs.
   */
  override def compileClasspath: T[Agg[PathRef]] = Task {
    val jarFiles = androidUnpackArchives().flatMap(_.classesJar) ++
      // need to filter, because it may include aar files, which are covered by androidUnpackArchives already
      super.resolvedRunIvyDeps().filter(_.path.ext == "jar")
    // TODO process metadata shipped with Android libs. It can have some rules with Target SDK, for example.
    // TODO support baseline profiles shipped with Android libs.
    super.compileClasspath().filter(_.path.ext != "aar") ++ jarFiles
  }

  /**
   * Specifies AAPT options for Android resource compilation.
   */
  def androidAaptOptions: T[Seq[String]] = Task { Seq("--auto-add-overlay") }

  def androidTransitiveResources: Target[Seq[PathRef]] = Task {
    T.traverse(transitiveModuleCompileModuleDeps) { m =>
      Task.Anon(m.resources())
    }().flatten
  }

  /**
   * Compiles Android resources and generates `R.java` and `res.apk`.
   *
   * @return [[(PathRef, Seq[PathRef]]] to the directory with application's `R.java` and `res.apk` and a list
   *         of the zip files containing the resource compiled files
   *
   * For more details on the aapt2 tool, refer to:
   * [[https://developer.android.com/tools/aapt2 aapt Documentation]]
   */
  def androidResources: T[(PathRef, Seq[PathRef])] = Task {
    val rClassDir = T.dest / rClassDirName
    val compiledResDir = T.dest / compiledResourcesDirName
    os.makeDir(compiledResDir)
    val compiledResources = collection.mutable.Buffer[os.Path]()

    val transitiveResources = androidTransitiveResources().map(_.path).filter(os.exists)

    val localResources = resources().map(_.path).filter(os.exists)

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
   * Adds the Android SDK JAR file to the classpath during the compilation process.
   */
  override def unmanagedClasspath: T[Agg[PathRef]] = Task {
    Agg(androidSdkModule().androidJarPath())
  }

  /**
   * Combines standard Java source directories with additional sources generated by
   * the Android resource generation step.
   */
  override def generatedSources: T[Seq[PathRef]] = Task {
    Seq(PathRef(androidResources()._1.path / rClassDirName)) ++ androidLibsRClasses()
  }

  /**
   * Converts the generated JAR file into a DEX file using the `d8` tool.
   *
   * @return os.Path to the Generated DEX File Directory
   */
  def androidDex: T[PathRef] = Task {

    val inheritedClassFiles = compileClasspath().map(_.path).filter(os.isDir)
      .flatMap(os.walk(_))
      .filter(os.isFile)
      .filter(_.ext == "class")
      .map(_.toString())

    val appCompiledFiles = os.walk(compile().classes.path)
      .filter(_.ext == "class")
      .map(_.toString) ++ inheritedClassFiles

    val libsJarFiles = compileClasspath()
      .filter(_ != androidSdkModule().androidJarPath())
      .filter(_.path.ext == "jar")
      .map(_.path.toString())

    val proguardFile = T.dest / "proguard-rules.pro"
    val knownProguardRules = androidUnpackArchives()
      // TODO need also collect rules from other modules,
      // but Android lib module doesn't yet exist
      .flatMap(_.proguardRules)
      .map(p => os.read(p.path))
      .appendedAll(mainDexPlatformRules)
      .appended(os.read(androidResources()._1.path / "main-dex-rules.pro"))
      .mkString("\n")
    os.write(proguardFile, knownProguardRules)

    val d8ArgsBuilder = Seq.newBuilder[String]

    d8ArgsBuilder += androidSdkModule().d8Path().path.toString

    if (androidIsDebug()) {
      d8ArgsBuilder += "--debug"
    } else {
      d8ArgsBuilder += "--release"
    }
    // TODO explore --incremental flag for incremental builds
    d8ArgsBuilder ++= Seq(
      "--output",
      Task.dest.toString(),
      "--lib",
      androidSdkModule().androidJarPath().path.toString(),
      "--min-api",
      androidMinSdk().toString,
      "--main-dex-rules",
      proguardFile.toString()
    ) ++ appCompiledFiles ++ libsJarFiles

    val d8Args = d8ArgsBuilder.result()

    T.log.info(s"Running d8 with the command: ${d8Args.mkString(" ")}")

    os.call(d8Args)

    PathRef(Task.dest)
  }

  /**
   * Packages DEX files and Android resources into an unsigned APK.
   *
   * @return A `PathRef` to the generated unsigned APK file (`app.unsigned.apk`).
   */
  def androidUnsignedApk: T[PathRef] = Task {
    val unsignedApk = Task.dest / "app.unsigned.apk"

    os.copy(androidResources()._1.path / "res.apk", unsignedApk)
    val dexFiles = os.walk(androidDex().path)
      .filter(_.ext == "dex")
      .map(os.zip.ZipSource.fromPath)
    // TODO probably need to merge all content, not only in META-INF of classes.jar, but also outside it
    val metaInf = androidLibsClassesJarMetaInf()
      .map(ref => {
        def metaInfRoot(p: os.Path): os.Path = {
          var current = p
          while (!current.endsWith(os.rel / "META-INF")) {
            current = current / os.up
          }
          current / os.up
        }
        val path = ref.path
        os.zip.ZipSource.fromPathTuple((path, path.subRelativeTo(metaInfRoot(path))))
      })
      .distinctBy(_.dest.get)

    // TODO generate aar-metadata.properties (for lib distribution, not in this module) or
    //  app-metadata.properties (for app distribution).
    // Example of aar-metadata.properties:
    // aarFormatVersion=1.0
    // aarMetadataVersion=1.0
    // minCompileSdk=33
    // minCompileSdkExtension=0
    // minAndroidGradlePluginVersion=1.0.0
    //
    // Example of app-metadata.properties:
    // appMetadataVersion=1.1
    // androidGradlePluginVersion=8.7.2
    os.zip(unsignedApk, dexFiles)
    os.zip(unsignedApk, metaInf)
    // TODO pack also native (.so) libraries

    PathRef(unsignedApk)
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
    Jvm.runSubprocess(
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

  /**
   * Classpath for the manifest merger run.
   */
  def manifestMergerClasspath: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(
        ivy"com.android.tools.build:manifest-merger:${androidSdkModule().manifestMergerVersion()}"
      )
    )
  }

  /**
   * Collect files from META-INF folder of classes.jar (not META-INF of aar in case of Android library).
   */
  def androidLibsClassesJarMetaInf: T[Seq[PathRef]] = Task {
    // ^ not the best name for the method, but this is to distinguish between META-INF of aar and META-INF
    // of classes.jar included in aar
    compileClasspath()
      .filter(ref =>
        ref.path.ext == "jar" &&
          ref != androidSdkModule().androidJarPath()
      )
      .flatMap(ref => {
        val dest = T.dest / ref.path.baseName
        os.unzip(ref.path, dest)
        val lookupPath = dest / "META-INF"
        if (os.exists(lookupPath)) {
          os.walk(lookupPath)
            .filter(os.isFile)
            .filterNot(f => isExcludedFromPackaging(f.relativeTo(lookupPath)))
        } else {
          Seq.empty[os.Path]
        }
      })
      .map(PathRef(_))
      .toSeq
  }

  /**
   * Optimizes the APK using the `zipalign` tool for better performance.
   *
   * For more details on the zipalign tool, refer to:
   * [[https://developer.android.com/tools/zipalign zipalign Documentation]]
   */
  def androidAlignedUnsignedApk: T[PathRef] = Task {
    val alignedApk: os.Path = Task.dest / "app.aligned.apk"

    os.call((
      androidSdkModule().zipalignPath().path,
      "-f",
      "-p",
      "4",
      androidUnsignedApk().path,
      alignedApk
    ))

    PathRef(alignedApk)
  }

  /**
   * Generates the command-line arguments required for Android app signing.
   *
   * Uses the release keystore if release build type is set; otherwise, defaults to a generated debug keystore.
   */
  def androidSignKeyDetails: T[Seq[String]] = Task {

    val keystorePass = {
      if (androidIsDebug()) debugKeyStorePass else androidReleaseKeyStorePass().get
    }
    val keyAlias = {
      if (androidIsDebug()) debugKeyAlias else androidReleaseKeyAlias().get
    }
    val keyPass = {
      if (androidIsDebug()) debugKeyPass else androidReleaseKeyPass().get
    }

    Seq(
      "--ks",
      androidKeystore().path.toString,
      "--ks-key-alias",
      keyAlias,
      "--ks-pass",
      s"pass:$keystorePass",
      "--key-pass",
      s"pass:$keyPass"
    )
  }

  /**
   * Signs the APK using a keystore to generate a final, distributable APK.
   *
   * The signing step is mandatory to distribute Android applications. It adds a cryptographic
   * signature to the APK, verifying its authenticity. This method uses the `apksigner` tool
   * along with a keystore file to sign the APK.
   *
   * If no keystore is available, a new one is generated using the `keytool` utility.
   *
   * For more details on the apksigner tool, refer to:
   * [[https://developer.android.com/tools/apksigner apksigner Documentation]]
   */
  def androidApk: T[PathRef] = Task {
    val signedApk = Task.dest / "app.apk"

    val signArgs = Seq(
      androidSdkModule().apksignerPath().path.toString,
      "sign",
      "--in",
      androidAlignedUnsignedApk().path.toString,
      "--out",
      signedApk.toString
    ) ++ androidSignKeyDetails()

    T.log.info(s"Calling apksigner with arguments: ${signArgs.mkString(" ")}")

    os.call(signArgs)

    PathRef(signedApk)
  }

  def androidLibsRClasses: T[Seq[PathRef]] = Task {
    // TODO do this better
    // So we have application R.java class generated by aapt2 link, which includes IDs of app resources + libs resources.
    // But we also need to have R.java classes for libraries. The process below is quite hacky and inefficient, because:
    // * it will generate R.java for the library even library has no resources declared
    // * R.java will have not only resource ID from this library, but from other libraries as well. They should be stripped.
    val rClassDir = androidResources()._1.path / rClassDirName
    val mainRClassPath = os.walk(rClassDir)
      .find(_.last == "R.java")
      .get

    val mainRClass = os.read(mainRClassPath)
    val libsPackages = androidUnpackArchives()
      .flatMap(_.manifest)
      .map(f => ((XML.loadFile(f.path.toIO) \\ "manifest").head \ "@package").head.toString())

    val libClasses: Seq[PathRef] = for {
      libPackage <- libsPackages
      libRClassPath = T.dest / libPackage.split('.') / "R.java"
      _ = os.write(
        libRClassPath,
        mainRClass.replaceAll("package .+;", s"package $libPackage;"),
        createFolders = true
      )
    } yield PathRef(libRClassPath)

    libClasses :+ PathRef(mainRClassPath)
  }

  /**
   * Runs the Android Lint tool to generate a report on code quality issues.
   *
   * This method utilizes Android Lint, a tool provided by the Android SDK,
   * to analyze the source code for potential bugs, performance issues, and
   * best practices compliance. It generates a report in the specified format.
   *
   * The report is saved in the task's destination directory as "report.html" by
   * default. It can be changed to other file format such as xml, txt and sarif.
   *
   * For more details on the Android Lint tool, refer to:
   * [[https://developer.android.com/studio/write/lint]]
   */
  def androidLintRun(): Command[PathRef] = Task.Command {

    val formats = androidLintReportFormat()

    // Generate the alternating flag and file os.Path strings
    val reportArg: Seq[String] = formats.flatMap { format =>
      Seq(format.flag, (Task.dest / s"report.${format.extension}").toString)
    }

    // Set os.Path to generated `.jar` files and/or `.class` files
    // TODO change to runClasspath once the runtime dependencies + source refs are fixed
    val cp = compileClasspath().map(_.path).filter(os.exists).mkString(":")

    // Set os.Path to the location of the project source codes
    val src = sources().map(_.path).filter(os.exists).mkString(":")

    // Set os.Path to the location of the project resource code
    val res = resources().map(_.path).filter(os.exists).mkString(":")

    // Prepare the lint configuration argument if the config os.Path is set
    val configArg = androidLintConfigPath().map(config =>
      Seq("--config", config.path.toString)
    ).getOrElse(Seq.empty)

    // Prepare the lint baseline argument if the baseline os.Path is set
    val baselineArg = androidLintBaselinePath().map(baseline =>
      Seq("--baseline", baseline.path.toString)
    ).getOrElse(Seq.empty)

    os.call(
      Seq(
        androidSdkModule().lintToolPath().path.toString,
        (millSourcePath / "src/main").toString,
        "--classpath",
        cp,
        "--sources",
        src,
        "--resources",
        res
      ) ++ configArg ++ baselineArg ++ reportArg ++ androidLintArgs(),
      check = androidLintAbortOnError
    )

    PathRef(Task.dest)
  }

  /** The name of the virtual device to be created by  [[createAndroidVirtualDevice]] */
  def androidVirtualDeviceIdentifier: String = "test"

  /** The device  id as listed from avdmanager list device. Default is medium_phone */
  def androidDeviceId: String = "medium_phone"

  /**
   * The target architecture of the virtual device to be created by  [[createAndroidVirtualDevice]]
   *  For example, "x86_64" (default). For a list of system images and their architectures,
   *  see the Android SDK Manager `sdkmanager --list`.
   */
  def androidEmulatorArchitecture: String = "x86_64"

  /**
   * Installs the user specified system image for the emulator
   * using sdkmanager . E.g. "system-images;android-35;google_apis_playstore;x86_64"
   */
  def sdkInstallSystemImage(): Command[String] = Task.Command {
    val image =
      s"system-images;${androidSdkModule().platformsVersion()};google_apis_playstore;$androidEmulatorArchitecture"
    T.log.info(s"Downloading $image")
    val installCall = os.call((
      androidSdkModule().sdkManagerPath().path,
      "--install",
      image
    ))

    if (installCall.exitCode != 0) {
      T.log.error(
        s"Error trying to install android emulator system image ${installCall.err.text()}"
      )
      throw new Exception(s"Failed to install system image $image: ${installCall.exitCode}")
    }
    image
  }

  /**
   * Creates the android virtual device identified in virtualDeviceIdentifier
   */
  def createAndroidVirtualDevice(): Command[String] = Task.Command {
    val command = os.call((
      androidSdkModule().avdPath().path,
      "create",
      "avd",
      "--name",
      androidVirtualDeviceIdentifier,
      "--package",
      sdkInstallSystemImage()(),
      "--device",
      androidDeviceId,
      "--force"
    ))
    if (command.exitCode != 0) {
      T.log.error(s"Failed to create android virtual device: ${command.err.text()}")
      throw new Exception(s"Failed to create android virtual device: ${command.exitCode}")
    }
    s"DeviceName: $androidVirtualDeviceIdentifier, DeviceId: $androidDeviceId"
  }

  /**
   * Deletes  the android device
   */
  def deleteAndroidVirtualDevice: T[os.CommandResult] = Task {
    os.call((
      androidSdkModule().avdPath().path,
      "delete",
      "avd",
      "--name",
      androidVirtualDeviceIdentifier
    ))
  }

  /**
   * Starts the android emulator and waits until it is booted
   *
   * @return The log line that indicates the emulator is ready
   */
  def startAndroidEmulator(): Command[String] = Task.Command(exclusive = true) {
    val ciSettings = Seq(
      "-no-snapshot-save",
      "-no-window",
      "-gpu",
      "swiftshader_indirect",
      "-noaudio",
      "-no-boot-anim",
      "-camera-back",
      "none"
    )
    val settings = if (sys.env.getOrElse("GITHUB_ACTIONS", "false") == "true")
      ciSettings
    else Seq.empty[String]
    val command = Seq(
      androidSdkModule().emulatorPath().path.toString(),
      "-delay-adb",
      "-port",
      androidEmulatorPort
    ) ++ settings ++ Seq("-avd", androidVirtualDeviceIdentifier)

    T.log.debug(s"Starting emulator with command ${command.mkString(" ")}")

    val startEmuCmd = os.spawn(
      command
    )

    val bootMessage: Option[String] = startEmuCmd.stdout.buffered.lines().filter(l => {
      T.log.debug(l.trim())
      l.contains("Boot completed in")
    }).findFirst().toScala

    if (bootMessage.isEmpty) {
      throw new Exception(s"Emulator failed to start: ${startEmuCmd.exitCode()}")
    }

    val emulator: String = waitForDevice(androidSdkModule().adbPath(), runningEmulator(), T.log)

    T.log.info(s"Emulator ${emulator} started with message $bootMessage")

    bootMessage.get
  }

  def adbDevices: T[String] = Task {
    os.call((androidSdkModule().adbPath().path, "devices", "-l")).out.text()
  }

  /**
   * Stops the android emulator
   */
  def stopAndroidEmulator: T[String] = Task {
    val emulator = runningEmulator()
    os.call(
      (androidSdkModule().adbPath().path, "-s", emulator, "emu", "kill")
    )
    emulator
  }

  /** The emulator port where adb connects to. Defaults to 5554 */
  def androidEmulatorPort: String = "5554"

  /**
   * Returns the emulator identifier for created from startAndroidEmulator
   * by iterating the adb device list
   */
  def runningEmulator: T[String] = Task {
    s"emulator-$androidEmulatorPort"
  }

  /**
   * Installs the app to the android device identified by this configuration in [[androidVirtualDeviceIdentifier]].
   *
   * @return The name of the device the app was installed to
   */
  def androidInstall: Target[String] = Task {
    val emulator = runningEmulator()

    os.call(
      (androidSdkModule().adbPath().path, "-s", emulator, "install", "-r", androidApk().path)
    )

    emulator
  }

  private def androidDebugKeystore: Task[PathRef] = Task(persistent = true) {
    val debugFileName = "mill-debug.jks"
    val globalDebugFileLocation = os.home / ".mill-android"

    if (!os.exists(globalDebugFileLocation)) {
      os.makeDir(globalDebugFileLocation)
    }

    val debugKeystoreFile = globalDebugFileLocation / debugFileName

    if (!os.exists(debugKeystoreFile)) {
      // TODO test on windows and mac and/or change implementation with java APIs
      os.call((
        "keytool",
        "-genkeypair",
        "-keystore",
        debugKeystoreFile,
        "-alias",
        debugKeyAlias,
        "-dname",
        "CN=MILL, OU=MILL, O=MILL, L=MILL, S=MILL, C=MILL",
        "-validity",
        "10000",
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-storepass",
        debugKeyStorePass,
        "-keypass",
        debugKeyPass
      ))
    }

    val debugKeystoreTaskFile = T.dest / debugFileName

    os.copy(debugKeystoreFile, debugKeystoreTaskFile)

    PathRef(debugKeystoreTaskFile)
  }

  protected def androidKeystore: T[PathRef] = Task {
    val pathRef = if (androidIsDebug()) {
      androidDebugKeystore()
    } else {
      androidReleaseKeyPath().get
    }
    pathRef
  }

  private def isExcludedFromPackaging(relPath: RelPath): Boolean = {
    val topPath = relPath.segments.head
    // TODO do this better
    // full list is here https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/packaging/PackagingOptionsUtils.kt;drc=85330e2f750acc1e1510623222d80e4b1ad5c8a2
    // but anyway it should be a packaging option DSL to configure additional excludes from the user side
    relPath.ext == "kotlin_module" ||
    relPath.ext == "kotlin_metadata" ||
    relPath.ext == "DSA" ||
    relPath.ext == "EC" ||
    relPath.ext == "SF" ||
    relPath.ext == "RSA" ||
    topPath == "maven" ||
    topPath == "proguard" ||
    topPath == "com.android.tools" ||
    relPath.last == "MANIFEST.MF" ||
    relPath.last == "LICENSE" ||
    relPath.last == "LICENSE.TXT" ||
    relPath.last == "NOTICE" ||
    relPath.last == "NOTICE.TXT" ||
    relPath.last == "kotlin-project-structure-metadata.json" ||
    relPath.last == "module-info.class"
  }

  private def waitForDevice(adbPath: PathRef, emulator: String, logger: Logger): String = {
    val BootedIndicator = "1"
    def getBootflag: String = {
      val result = os.call(
        (
          adbPath.path,
          "-s",
          emulator,
          "shell",
          "getprop",
          "sys.boot_completed"
        ),
        check = false
      )
      if (result.exitCode != 0)
        "0"
      else
        result.out.trim()
    }

    var bootflag = getBootflag
    var triesLeft = 25

    while (bootflag != BootedIndicator && triesLeft > 0) {
      logger.debug(s"Waiting for device to boot. Bootflag: $bootflag . Tries left ${triesLeft}")
      Thread.sleep(1000)
      triesLeft -= 1
      bootflag = getBootflag
    }

    if (bootflag == BootedIndicator)
      emulator
    else
      throw new Exception("Device failed to boot")
  }

  trait AndroidAppTests extends JavaTests {
    private def testPath = parent.millSourcePath / "src/test"

    override def sources: T[Seq[PathRef]] = Seq(PathRef(testPath / "java"))

    override def resources: T[Seq[PathRef]] = Task.Sources(Seq(PathRef(testPath / "res")))

    override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
      baseDirectory = Some(testPath),
      canTest = true
    )

  }

  trait AndroidAppInstrumentedTests extends AndroidAppModule with AndroidTestModule {
    private def androidMainSourcePath = parent.millSourcePath
    private def androidTestPath = androidMainSourcePath / "src/androidTest"

    override def moduleDeps: Seq[JavaModule] = Seq(parent)

    override def androidCompileSdk: T[Int] = parent.androidCompileSdk
    override def androidMinSdk: T[Int] = parent.androidMinSdk
    override def androidTargetSdk: T[Int] = parent.androidTargetSdk

    override def androidIsDebug: T[Boolean] = parent.androidIsDebug

    override def androidReleaseKeyAlias: T[Option[String]] = parent.androidReleaseKeyAlias
    override def androidReleaseKeyName: T[Option[String]] = parent.androidReleaseKeyName
    override def androidReleaseKeyPass: T[Option[String]] = parent.androidReleaseKeyPass
    override def androidReleaseKeyStorePass: T[Option[String]] = parent.androidReleaseKeyStorePass
    override def androidReleaseKeyPath: T[Option[PathRef]] = parent.androidReleaseKeyPath

    override def androidEmulatorPort: String = parent.androidEmulatorPort

    override def sources: T[Seq[PathRef]] = Seq(PathRef(androidTestPath / "java"))

    /** The resources in res directories of both main source and androidTest sources */
    override def resources: T[Seq[PathRef]] = Task.Sources {
      val libResFolders = androidUnpackArchives().flatMap(_.resources)
      libResFolders ++ Seq(PathRef(androidTestPath / "res"))
    }

    override def generatedSources: T[Seq[PathRef]] = Task.Sources(Seq.empty[PathRef])

    /* TODO on debug work, an AndroidManifest.xml with debug and instrumentation settings
     * will need to be created. Then this needs to point to the location of that debug
     * AndroidManifest.xml
     */
    override def androidManifest: Task[PathRef] = parent.androidManifest

    override def androidVirtualDeviceIdentifier: String = parent.androidVirtualDeviceIdentifier
    override def androidEmulatorArchitecture: String = parent.androidEmulatorArchitecture

    def instrumentationPackage: String

    def testFramework: T[String]

    override def androidInstall: Target[String] = Task {
      val emulator = runningEmulator()
      os.call(
        (
          androidSdkModule().adbPath().path,
          "-s",
          emulator,
          "install",
          "-r",
          androidInstantApk().path
        )
      )
      emulator
    }

    override def testTask(
        args: Task[Seq[String]],
        globSelectors: Task[Seq[String]]
    ): Task[(String, Seq[TestResult])] = Task {
      val device = androidInstall()

      val instrumentOutput = os.proc(
        (
          androidSdkModule().adbPath().path,
          "-s",
          device,
          "shell",
          "am",
          "instrument",
          "-w",
          "-r",
          s"$instrumentationPackage/${testFramework()}"
        )
      ).spawn()

      val outputReader = instrumentOutput.stdout.buffered

      val (doneMsg, results) = InstrumentationOutput.parseTestOutputStream(outputReader)(T.log)
      val res = TestModule.handleResults(doneMsg, results, T.ctx(), testReportXml())

      res

    }

    /** Builds the apk including the integration tests (e.g. from androidTest) */
    def androidInstantApk: T[PathRef] = androidApk

    @internal
    override def bspBuildTarget: BspBuildTarget = super[AndroidTestModule].bspBuildTarget.copy(
      baseDirectory = Some(androidTestPath),
      canRun = false
    )

  }

}

// TODO this maybe not needed at all, maybe it is better just to return the list of the root folders and then reference
//  each item type manually.
/**
 * Descriptor of unpacked `.aar` dependency structure.
 * @param name dependency name
 * @param classesJar path to the classes.jar
 * @param proguardRules path to the proguard rules
 * @param resources path to the res folder
 * @param manifest path to the AndroidManifest.xml
 * @param lintJar path to the lint.jar file
 * @param metaInf path to the META-INF folder
 * @param nativeLibs path to the native .so libraries root folder
 * @param baselineProfile path to the baseline profile file
 * @param rTxtFile path to the R.txt
 * @param publicResFile path to public.txt file
 */
case class UnpackedDep(
    name: String,
    classesJar: Option[PathRef],
    proguardRules: Option[PathRef],
    resources: Option[PathRef],
    manifest: Option[PathRef],
    lintJar: Option[PathRef],
    metaInf: Option[PathRef],
    nativeLibs: Option[PathRef],
    baselineProfile: Option[PathRef],
    rTxtFile: Option[PathRef],
    publicResFile: Option[PathRef]
)

object UnpackedDep {
  implicit val rw: ReadWriter[UnpackedDep] = macroRW
}
