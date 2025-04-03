package mill.javalib.android

import coursier.Repository
import mill.api.PathRef
import mill.define.{ModuleRef, Target, Task}
import mill.T
import mill.scalalib.*
import mill.util.Jvm
import os.RelPath

import scala.xml.XML

trait AndroidModule extends JavaModule {

  private val rClassDirName = "RClass"
  private val compiledResourcesDirName = "compiled-resources"

  protected val debugKeyStorePass = "mill-android"
  protected val debugKeyAlias = "mill-android"
  protected val debugKeyPass = "mill-android"

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
   * Provides os.Path to an XML file containing configuration and metadata about your android application.
   */
  def androidManifest: Task[PathRef] = Task.Source("src/main/AndroidManifest.xml")

  /**
   * Controls debug vs release build type. Default is `true`, meaning debug build will be generated.
   *
   * This option will probably go away in the future once build variants are supported.
   */
  def androidIsDebug: T[Boolean] = true

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

  def androidTransitiveResources: Target[Seq[PathRef]] = Task {
    T.traverse(transitiveModuleCompileModuleDeps) { m =>
      Task.Anon(m.resources())
    }().flatten
  }

  override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    super.repositoriesTask() :+ AndroidSdkModule.mavenGoogle
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

  /**
   * Classpath for the manifest merger run.
   */
  def manifestMergerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        ivy"com.android.tools.build:manifest-merger:${androidSdkModule().manifestMergerVersion()}"
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
    // In Gradle terms using only `resolvedRunIvyDeps` won't be complete, because source modules can be also
    // api/implementation, but Mill has no such configurations.
    val aarFiles = (super.compileClasspath() ++ super.resolvedRunIvyDeps())
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
      val resources = pathOption(extractDir / "res")
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
        resources,
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
    val rClassDir = androidResources()._1.path / rClassDirName
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

  /**
   * Compiles Android resources and generates `R.java` and `res.apk`.
   *
   * @return [[(PathRef, Seq[PathRef]]] to the directory with application's `R.java` and `res.apk` and a list
   *         of the zip files containing the resource compiled files
   *
   *         For more details on the aapt2 tool, refer to:
   *         [[https://developer.android.com/tools/aapt2 aapt Documentation]]
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

  // TODO alias, keystore pass and pass below are sensitive credentials and shouldn't be leaked to disk/console.
  // In the current state they are leaked, because Task dumps output to the json.
  // Should be fixed ASAP.

  /**
   * Name of the key alias in the release keystore. Default is not set.
   */
  def androidReleaseKeyAlias: T[Option[String]] = Task {
    None
  }

  /**
   * Name of the release keystore file. Default is not set.
   */
  def androidReleaseKeyName: T[Option[String]] = Task {
    None
  }

  /**
   * Password for the release key. Default is not set.
   */
  def androidReleaseKeyPass: T[Option[String]] = Task {
    None
  }

  /**
   * Password for the release keystore. Default is not set.
   */
  def androidReleaseKeyStorePass: T[Option[String]] = Task {
    None
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
   * Default os.Path to the keystore file, derived from `androidReleaseKeyName()`.
   * Users can customize the keystore file name to change this path.
   */
  def androidReleaseKeyPath: T[Option[PathRef]] = Task {
    androidReleaseKeyName().map(name => PathRef(moduleDir / name))
  }

  /** The name of the virtual device to be created by  [[createAndroidVirtualDevice]] */
  def androidVirtualDeviceIdentifier: String = "test"

  /**
   * The target architecture of the virtual device to be created by  [[createAndroidVirtualDevice]]
   * For example, "x86_64" (default). For a list of system images and their architectures,
   * see the Android SDK Manager `sdkmanager --list`.
   */
  def androidEmulatorArchitecture: String = "x86_64"

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

}
