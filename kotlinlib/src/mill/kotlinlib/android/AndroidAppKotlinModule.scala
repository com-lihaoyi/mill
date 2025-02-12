package mill.kotlinlib.android

import coursier.Dependency
import coursier.core.Reconciliation
import coursier.params.ResolutionParams
import coursier.util.ModuleMatchers
import mill.{T, Task}
import mill.api.PathRef
import mill.define.{Command, ModuleRef, Task}
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule}
import mill.javalib.android.{AndroidAppModule, AndroidSdkModule}
import mill.scalalib.{JavaModule, TestModule}
import mill.scalalib.TestModule.Junit5

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
trait AndroidAppKotlinModule extends AndroidAppModule with KotlinModule { outer =>

  override def sources: T[Seq[PathRef]] =
    super[AndroidAppModule].sources() :+ PathRef(moduleDir / "src/main/kotlin")

  override def kotlincOptions = super.kotlincOptions() ++ {
    if (androidEnableCompose()) {
      Seq(
        // TODO expose Compose configuration options
        // https://kotlinlang.org/docs/compose-compiler-options.html possible options
        s"-Xplugin=${composeProcessor().path}"
      )
    } else Seq.empty
  }

  /**
   * Enable Jetpack Compose support in the module. Default is `false`.
   */
  def androidEnableCompose: T[Boolean] = false

  private def composeProcessor = Task {
    // cut-off usages for Kotlin 1.x, because of the need to maintain the table of
    // Compose compiler version -> Kotlin version
    if (kotlinVersion().startsWith("1"))
      throw new IllegalStateException("Compose can be used only with Kotlin version 2 or newer.")
    defaultResolver().resolveDeps(
      Seq(
        ivy"org.jetbrains.kotlin:kotlin-compose-compiler-plugin:${kotlinVersion()}"
      )
    ).head
  }

  trait AndroidAppKotlinTests extends AndroidAppTests with KotlinTests {
    override def sources: T[Seq[PathRef]] =
      super[AndroidAppTests].sources() ++ Seq(PathRef(outer.moduleDir / "src/test/kotlin"))
  }

  trait AndroidAppKotlinInstrumentedTests extends AndroidAppKotlinModule
      with AndroidAppInstrumentedTests {

    override final def kotlinVersion = outer.kotlinVersion
    override final def androidSdkModule = outer.androidSdkModule

    override def sources: T[Seq[PathRef]] =
      super[AndroidAppInstrumentedTests].sources() :+ PathRef(
        outer.moduleDir / "src/androidTest/kotlin"
      )
  }

  trait AndroidAppKotlinScreenshotTests extends AndroidAppKotlinModule with TestModule with Junit5 {

    /* There are no testclasses for screenshot tests, just the engine running a diff over the images */
    override def discoveredTestClasses: T[Seq[String]] = Task { Seq.empty[String] }

    override def mapDependencies: Task[Dependency => Dependency] = outer.mapDependencies

    override def androidCompileSdk: T[Int] = outer.androidCompileSdk

    override def androidMergedManifest: T[PathRef] = outer.androidMergedManifest

    override def androidSdkModule: ModuleRef[AndroidSdkModule] = outer.androidSdkModule

    def layoutLibVersion: String = "14.0.9"
    def composePreviewRendererVersion: String = "0.0.1-alpha08"

    def namespace: String

    override def moduleDeps: Seq[JavaModule] = Seq(outer)

    override final def kotlinVersion = outer.kotlinVersion

    override def kotlincOptions = super.kotlincOptions() ++ Seq(
      s"-Xplugin=${composeProcessor().path}"
    )

    override def sources: T[Seq[PathRef]] = Task.Sources(
      Seq(
        PathRef(outer.moduleDir / "src/screenshotTest/kotlin"),
        PathRef(outer.moduleDir / "src/screenshotTest/java")
      )
    )

    /**
     * The compose-preview-renderer jar executable that generates the screenshots.
     * For more information see [[https://developer.android.com/studio/preview/compose-screenshot-testing]]
     */
    def composePreviewRenderer: T[Seq[PathRef]] = Task {
      defaultResolver().resolveDeps(
        Seq(
          ivy"com.android.tools.compose:compose-preview-renderer:$composePreviewRendererVersion"
        )
      )
    }

    final def layoutLibRenderer: T[Seq[PathRef]] = Task {
      defaultResolver().resolveDeps(
        Seq(
          ivy"com.android.tools.layoutlib:layoutlib:$layoutLibVersion"
        )
      )
    }

    final def layoutLibRuntime: T[Seq[PathRef]] = Task {
      defaultResolver().resolveDeps(
        Seq(
          ivy"com.android.tools.layoutlib:layoutlib-runtime:$layoutLibVersion"
        )
      )
    }

    final def layoutLibFrameworkRes: T[Seq[PathRef]] = Task {
      defaultResolver().resolveDeps(
        Seq(
          ivy"com.android.tools.layoutlib:layoutlib-resources:$layoutLibVersion"
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

    def uiToolingVersion: String = "1.7.6"

    /*
     * A relaxed resolution policy, as this module runs the tests
     * on the host machine, outside of Android, but it still needs
     * the android ui dependencies to work, so production wise this is
     * low risk.
     * @return
     */
    override def resolutionParams: Task[ResolutionParams] = Task.Anon {
      val params = super.resolutionParams()
      relaxedDependencyReconciliation(params)
    }

    private val relaxedDependencyReconciliation: ResolutionParams => ResolutionParams =
      _.withReconciliation(Seq(ModuleMatchers.all -> Reconciliation.Relaxed))

    override def generatedSources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

    override def resources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

    override def mandatoryIvyDeps: T[Seq[Dep]] = super.mandatoryIvyDeps() ++
      Seq(
        ivy"androidx.compose.ui:ui:$uiToolingVersion",
        ivy"androidx.compose.ui:ui-tooling:$uiToolingVersion",
        ivy"androidx.compose.ui:ui-test-manifest:$uiToolingVersion",
        ivy"androidx.compose.ui:ui-tooling-preview-android:$uiToolingVersion"
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
        projectClassPath =
          Seq(compile().classes.path.toString(), androidResources()._1.path.toString),
        screenshots = androidDiscoveredPreviews()._2,
        namespace = namespace,
        resourceApkPath = resourceApkPath().path.toString(),
        resultsFilePath = resultsFilePath.toString()
      )
      os.write(cliArgsFile, upickle.default.write(cliArgs))

      PathRef(cliArgsFile)

    }

    private def resourceApkPath: Task[PathRef] = Task {
      PathRef(outer.androidResources()._1.path / "res.apk")
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
        mainClass = "com.android.tools.render.compose.MainKt",
        classPath =
          composePreviewRenderer().map(_.path).toVector ++
            layoutLibRenderer().map(_.path).toVector,
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
    def androidDiscoveredPreviews: T[(PathRef, Seq[ComposeRenderer.Screenshot])] = Task {

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
        upickle.default.write(Map("screenshots" -> screenshotConfigurations))
      )

      PathRef(androidDiscoveredPreviewsPath) -> screenshotConfigurations
    }

    private def diffImageDirPath: Task[PathRef] = Task {
      val diffImageDir = Task.dest / "diff-images"
      PathRef(diffImageDir)
    }

    override def forkArgs: T[Seq[String]] = super.forkArgs() ++ testJvmArgs()
    override def runClasspath: T[Seq[PathRef]] =
      (androidPreviewScreenshotTestEngineClasspath() ++ compileClasspath()).distinct

    def androidPreviewScreenshotTestEngineClasspath: T[Seq[PathRef]] = Task {
      defaultResolver().resolveDeps(
        Seq(
          ivy"com.android.tools.screenshot:screenshot-validation-junit-engine:0.0.1-alpha08"
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
        "previews-discovered" -> androidDiscoveredPreviews()._1.path.toString(),
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

}
