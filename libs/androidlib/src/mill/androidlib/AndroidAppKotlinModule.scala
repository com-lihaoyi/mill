package mill.androidlib

import coursier.params.ResolutionParams
import mill.api.{ModuleRef, PathRef, Task}
import mill.kotlinlib.{Dep, DepSyntax}
import mill.javalib.TestModule.Junit5
import mill.javalib.{JavaModule, TestModule}
import mill.*
import mill.api.JsonFormatters.given
import mill.androidlib.Versions

/**
 * Trait for building Android applications using the Mill build tool.
 *
 * This trait defines all the necessary steps for building an Android app from Kotlin sources,
 * integrating both Android-specific tasks and generic Kotlin tasks by extending the
 * [[KotlinModule]] (for standard Kotlin tasks)
 * and [[AndroidAppModule]] (for Android Application Workflow Process).
 *
 * It provides a structured way to handle various steps in the Android app build process,
 * including compiling Kotlin sources, creating DEX files, generating resources, packaging
 * APKs, optimizing, and signing APKs.
 *
 * [[https://developer.android.com/studio Android Studio Documentation]]
 */
@mill.api.experimental
trait AndroidAppKotlinModule extends AndroidKotlinModule, AndroidAppModule { outer =>

  private def kotlinSources = Task.Sources("src/main/kotlin")
  override def sources: T[Seq[PathRef]] =
    super[AndroidAppModule].sources() ++ kotlinSources()

  trait AndroidAppKotlinTests extends AndroidKotlinTestModule

  trait AndroidAppKotlinInstrumentedTests extends AndroidAppInstrumentedTests,
        AndroidAppKotlinModule {

    override final def kotlinVersion: T[String] = outer.kotlinVersion
    override final def androidSdkModule: ModuleRef[AndroidSdkModule] = outer.androidSdkModule
    override def resolutionParams: Task[ResolutionParams] = Task.Anon(outer.resolutionParams())

    private def kotlinSources = Task.Sources("src/androidTest/kotlin")

    override def kotlincPluginMvnDeps: T[Seq[Dep]] = outer.kotlincPluginMvnDeps()

    override def sources: T[Seq[PathRef]] =
      super[AndroidAppInstrumentedTests].sources() ++ kotlinSources()

  }

  trait AndroidAppKotlinScreenshotTests extends AndroidAppKotlinModule, TestModule, Junit5 {

    override def androidApplicationId: String = outer.androidApplicationId

    override def discoveredTestClasses: T[Seq[String]] = Task {
      super[TestModule].discoveredTestClasses()
    }

    /**
     * Screenshot tests cannot be run in parallel for now
     */
    override def testParallelism: T[Boolean] = Task { false }

    override def androidApplicationNamespace: String = outer.androidApplicationNamespace

    override def androidCompileSdk: T[Int] = outer.androidCompileSdk()

    override def androidSdkModule: ModuleRef[AndroidSdkModule] = outer.androidSdkModule

    override def androidManifest: T[PathRef] = outer.androidManifest()
    override def androidMergedManifest: T[PathRef] = outer.androidMergedManifest()

    def layoutLibVersion: T[String] = Task {
      Versions.layoutLibVersion
    }
    def composePreviewRendererVersion: T[String] = Task {
      Versions.composePreviewRendererVersion
    }

    def screenshotValidationJunitEngineVersion: T[String] = Task {
      Versions.screenshotValidationJunitEngineVersion
    }

    override def moduleDeps: Seq[JavaModule] = Seq(outer)

    override final def kotlinVersion = outer.kotlinVersion()

    override def sources: T[Seq[PathRef]] = Task.Sources(
      outer.moduleDir / "src/screenshotTest/kotlin",
      outer.moduleDir / "src/screenshotTest/java"
    )

    /**
     * The compose-preview-renderer jar executable that generates the screenshots.
     * For more information see [[https://developer.android.com/studio/preview/compose-screenshot-testing]]
     */
    def composePreviewRenderer: T[Seq[PathRef]] = Task {
      defaultResolver().classpath(
        Seq(
          mvn"com.android.tools.compose:compose-preview-renderer:${composePreviewRendererVersion()}"
        )
      )
    }

    final def layoutLibRenderer: T[Seq[PathRef]] = Task {
      defaultResolver().classpath(
        Seq(
          mvn"com.android.tools.layoutlib:layoutlib:${layoutLibVersion()}"
        )
      )
    }

    final def layoutLibRuntime: T[Seq[PathRef]] = Task {
      defaultResolver().classpath(
        Seq(
          mvn"com.android.tools.layoutlib:layoutlib-runtime:${layoutLibVersion()}"
        )
      )
    }

    final def layoutLibFrameworkRes: T[Seq[PathRef]] = Task {
      defaultResolver().classpath(
        Seq(
          mvn"com.android.tools.layoutlib:layoutlib-resources:${layoutLibVersion()}"
        )
      )
    }

    final def layoutLibRuntimePath: T[PathRef] = Task {
      val extractDestination = Task.dest / "layoutLib"
      val layoutLibJar = layoutLibRuntime().head
      os.unzip(layoutLibJar.path, extractDestination)
      val frameworkResPath = extractDestination / "data/framework_res.jar"
      os.copy(from = layoutLibFrameworkRes().head.path, to = frameworkResPath)
      PathRef(extractDestination)
    }

    def uiToolingVersion: T[String] = Task {
      Versions.uiToolingVersion
    }

    override def resolvedMvnDeps: T[Seq[PathRef]] = Task {
      defaultResolver().classpath(
        outer.mvnDeps() ++ mvnDeps()
      )
    }

    override def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++
      Seq(
        mvn"androidx.compose.ui:ui:${uiToolingVersion()}",
        mvn"androidx.compose.ui:ui-tooling:${uiToolingVersion()}",
        mvn"androidx.compose.ui:ui-test-manifest:${uiToolingVersion()}",
        mvn"androidx.compose.ui:ui-tooling-preview-android:${uiToolingVersion()}"
      )

    /** The location to store the generated preview summary */
    def androidScreenshotGeneratedResults: T[PathRef] =
      Task {
        PathRef(Task.dest / "results.json")
      }

    /**
     * The location that the renderer results summary is, in the test engine's expected format
     * i.e. by renaming the screenshotResults key to screenshots
     */
    def androidScreenshotResults: T[PathRef] = Task {
      PathRef(Task.dest / "results.json")
    }

    private def screenshotResults: Task[PathRef] = Task {
      val dir = Task.dest / "generated-screenshots"
      os.makeDir(dir)
      PathRef(dir)
    }

    override def androidEnableCompose: T[Boolean] = Task { true }

    /**
     * Generates the json with the cli arguments for
     * compose-preview-renderer as in
     * [[https://android.googlesource.com/platform/tools/base/+/61923408e5f7dc20f0840844597f9dde17453a0f/preview/screenshot/screenshot-test-gradle-plugin/src/main/java/com/android/compose/screenshot/tasks/PreviewScreenshotRenderTask.kt#157]]
     * @return
     */
    def composePreviewArgs: T[PathRef] = Task {
      val output = screenshotResults().path
      val metadataFolder = Task.dest / "meta-data"
      val cliArgsFile = Task.dest / "cli_arguments.json"
      val resultsFilePath = androidScreenshotGeneratedResults().path

      val cliArgs = ComposeRenderer.Args(
        fontsPath = androidSdkModule().fontsPath().toString,
        layoutlibPath = layoutLibRuntimePath().path.toString(),
        outputFolder = output.toString(),
        metaDataFolder = metadataFolder.toString(),
        classPath = compileClasspath().map(_.path.toString()),
        projectClassPath = Seq(
          compile().classes.path.toString(),
          outer.compile().classes.path.toString(),
          androidProcessedResources().path.toString
        ),
        screenshots = androidDiscoveredPreviews().screenshotConfigs,
        namespace = androidApplicationNamespace,
        resourceApkPath = resourceApkPath().path.toString(),
        resultsFilePath = resultsFilePath.toString()
      )
      os.write(cliArgsFile, upickle.write(cliArgs))

      PathRef(cliArgsFile)

    }

    private def resourceApkPath: Task[PathRef] = Task {
      PathRef(outer.androidLinkedResources().path / "apk/res.apk")
    }

    // TODO previews must be source controlled to be used as a base
    // for subsequent runs
    /**
     * Invokes the preview renderer similar to
     * [[https://android.googlesource.com/platform/tools/base/+/61923408e5f7dc20f0840844597f9dde17453a0f/preview/screenshot/screenshot-test-gradle-plugin/src/main/java/com/android/compose/screenshot/tasks/PreviewRenderWorkAction.kt]]
     * @return
     */
    def generatePreviews(): Command[Seq[PathRef]] = Task.Command(exclusive = true) {
      val previewGenCmd = mill.util.Jvm.callProcess(
        mainClass = "com.android.tools.render.common.MainKt",
        classPath =
          composePreviewRenderer().map(_.path).toVector ++ layoutLibRenderer().map(_.path).toVector,
        jvmArgs = Seq(
          "-Dlayoutlib.thread.profile.timeoutms=10000",
          "-Djava.security.manager=allow"
        ),
        mainArgs = Seq(composePreviewArgs().path.toString()),
        cwd = Task.dest,
        stdin = os.Inherit,
        stdout = os.Inherit
      )

      Task.log.info(s"Generate preview command ${previewGenCmd.command.mkString(" ")}")

      previewGenCmd.out.lines().foreach(Task.log.info(_))

      Seq.from(os.walk(screenshotResults().path).filter(_.ext == "png").map(PathRef(_)))
    }

    // TODO add more devices for default, i.e. the same as in AGP
    /**
     * Defines a list of specifications to create previews against.
     *
     * For more information see [[https://developer.android.com/studio/preview/compose-screenshot-testing]]
     * @return
     */
    def androidScreenshotDeviceConfigurations: Seq[ComposeRenderer.PreviewParams] = Seq(
      ComposeRenderer.PreviewParams(
        device = "spec:id=reference_phone,shape=Normal,width=411,height=891,unit=dp,dpi=420",
        name = "Phone",
        showSystemUi = "true"
      )
    )

    /* TODO we can do method discovery with reflection or junit tools */
    def androidScreenshotTestMethods: Seq[(String, Seq[String])]

    /* TODO enhance with hash (sha-1) and auto-detection/generation of methodFQN */
    def androidDiscoveredPreviews: T[(
        previewsDiscoveredJsonFile: PathRef,
        screenshotConfigs: Seq[ComposeRenderer.Screenshot]
    )] = Task {

      val androidDiscoveredPreviewsPath = Task.dest / "previews_discovered.json"

      val screenshotConfigurations = for {
        (methodFQN, methodParams) <- androidScreenshotTestMethods
        previewParams <- androidScreenshotDeviceConfigurations
      } yield ComposeRenderer.Screenshot(
        methodFQN = methodFQN,
        methodParams = methodParams,
        previewParams = previewParams,
        previewId = s"${methodFQN}_${previewParams.device}"
      )
      os.write(
        androidDiscoveredPreviewsPath,
        upickle.write(Map("screenshots" -> screenshotConfigurations))
      )

      (
        previewsDiscoveredJsonFile = PathRef(androidDiscoveredPreviewsPath),
        screenshotConfigs = screenshotConfigurations
      )
    }

    private def diffImageDirPath: Task[PathRef] = Task {
      val diffImageDir = Task.dest / "diff-images"
      PathRef(diffImageDir)
    }

    override def forkArgs: T[Seq[String]] = super.forkArgs() ++ testJvmArgs()
    override def runClasspath: T[Seq[PathRef]] =
      super.runClasspath() ++ androidPreviewScreenshotTestEngineClasspath() ++ compileClasspath()

    def androidPreviewScreenshotTestEngineClasspath: T[Seq[PathRef]] = Task {
      defaultResolver().classpath(
        Seq(
          mvn"com.android.tools.screenshot:screenshot-validation-junit-engine:${screenshotValidationJunitEngineVersion()}"
        )
      )
    }

    def androidScreenshotTestResultDir: T[PathRef] = Task {
      PathRef(Task.dest)
    }

    /** Threshold of how strict the image diff should be */
    def androidScreenshotTestDiffThreshold = 0.001
    /*
    As defined in
    [[https://android.googlesource.com/platform/tools/base/+/61923408e5f7dc20f0840844597f9dde17453a0f/preview/screenshot/screenshot-test-gradle-plugin/src/main/java/com/android/compose/screenshot/tasks/PreviewScreenshotValidationTask.kt?#84]]
     */
    private def testJvmArgs: T[Seq[String]] = Task {
      val params = Map(
        "previews-discovered" -> androidDiscoveredPreviews().previewsDiscoveredJsonFile.path.toString(),
        "referenceImageDirPath" -> screenshotResults().path.toString(),
        "diffImageDirPath" -> diffImageDirPath().path.toString,
        "renderResultsFilePath" -> androidScreenshotGeneratedResults().path.toString,
        "renderTaskOutputDir" -> screenshotResults().path.toString(),
        "resultsDirPath" -> androidScreenshotTestResultDir().path.toString(),
        "threshold" -> androidScreenshotTestDiffThreshold.toString
      )

      params.map { case (k, v) => s"$JvmTestArg$k=$v" }.toSeq
    }

    private val JvmTestArg = "-Dcom.android.tools.preview.screenshot.junit.engine."

  }

  trait AndroidAppKotlinVariantModule extends AndroidKotlinVariantModule, AndroidAppKotlinModule {
    override def sources: T[Seq[PathRef]] = outer.sources
    override def resolutionParams: Task[ResolutionParams] = Task.Anon(outer.resolutionParams())
  }

  trait AndroidKotlinReleaseModule extends AndroidReleaseModule, AndroidAppKotlinVariantModule

}
