package mill.androidlib

import coursier.params.ResolutionParams
import mill.api.{BuildCtx, ModuleRef, PathRef, Task}
import mill.kotlinlib.{Dep, DepSyntax}
import mill.javalib.TestModule.Junit5
import mill.javalib.testrunner.TestResult
import mill.javalib.{JavaModule, TestModule, TestModuleUtil}
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

  /**
   * Screenshot tests of the Compose `@Preview`s of the application, run by the
   * [[https://developer.android.com/studio/preview/compose-screenshot-testing Preview Screenshot Test Engine]].
   * Only the previews annotated with `@PreviewTest` (from `com.android.tools.screenshot`) are tested,
   * previews without it are skipped, so a module with none of them runs no tests.
   *
   * `testForked` validates the previews against the reference images in
   * [[androidScreenshotReferenceImageDir]], and `updateScreenshots` records them.
   */
  trait AndroidAppKotlinScreenshotTests extends AndroidAppKotlinModule, TestModule, Junit5 {

    override def androidApplicationId: T[String] = outer.androidApplicationId

    override def discoveredTestClasses: T[Seq[String]] = Task {
      super[TestModule].discoveredTestClasses()
    }

    /**
     * Screenshot tests cannot be run in parallel for now
     */
    override def testParallelism: T[Boolean] = Task { false }

    override def androidApplicationNamespace: T[String] = outer.androidApplicationNamespace

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
     * The compose-preview-renderer that the test engine uses to render the previews.
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
        mvn"androidx.compose.ui:ui-tooling-preview-android:${uiToolingVersion()}",
        // provides the @PreviewTest annotation that marks which previews are screenshot tests
        mvn"com.android.tools.screenshot:screenshot-validation-api:${screenshotValidationJunitEngineVersion()}"
      )

    override def androidEnableCompose: T[Boolean] = Task { true }

    /**
     * The directory holding the reference images that screenshots are compared against.
     * It is meant to be source controlled. Create or refresh it with [[updateScreenshots]].
     */
    def androidScreenshotReferenceImageDir: T[PathRef] = Task.Source {
      outer.moduleDir / "src/screenshotTest/reference"
    }

    /**
     * Renders the previews and records the result as the new reference images
     * (in [[androidScreenshotReferenceImageDir]]), instead of validating against them like
     * `testForked` does. Run it the first time, and whenever the UI intentionally changes.
     *
     * It runs the tests exactly like `testForked`, with the test engine's recording mode
     * enabled on top of the usual [[forkArgs]].
     */
    def updateScreenshots(args: String*): Command[(
        msg: String,
        results: Seq[TestResult]
    )] = Task.Command(exclusive = true) {
      screenshotTestTask(
        Task.Anon(testArgsDefault() ++ args),
        Task.Anon(Seq.empty[String]),
        Task.Anon(Seq(s"${JvmTestArg}TestOption.recordingModeEnabled=true"))
      )()
    }

    override protected def testTask(
        args: Task[Seq[String]],
        globSelectors: Task[Seq[String]]
    ): Task[(msg: String, results: Seq[TestResult])] =
      screenshotTestTask(args, globSelectors, Task.Anon(Seq.empty[String]))

    // `TestModule.testTask` always runs with `forkArgs()`, so we rebuild its `TestModuleUtil`
    // arguments here only to append `extraForkArgs`. Like AGP does, we also clear the images of
    // the previous run first, so that stale rendered or diff images don't linger.
    private def screenshotTestTask(
        args: Task[Seq[String]],
        globSelectors: Task[Seq[String]],
        extraForkArgs: Task[Seq[String]]
    ): Task[(msg: String, results: Seq[TestResult])] = Task.Anon {
      for (
        dir <- Seq(androidScreenshotTestResultDir().path / "rendered", diffImageDirPath().path)
      ) {
        os.remove.all(dir)
        os.makeDir.all(dir)
      }
      TestModuleUtil(
        testUseArgsFile(),
        forkArgs() ++ extraForkArgs(),
        globSelectors(),
        jvmWorker().scalalibClasspath(),
        resources(),
        testFramework(),
        runClasspath(),
        testClasspath(),
        args(),
        testForkGrouping(),
        jvmWorker().testrunnerEntrypointClasspath(),
        allForkEnv(),
        testSandboxWorkingDir(),
        forkWorkingDir(),
        testReportXml(),
        javaHome().map(_.path),
        testParallelism(),
        testLogLevel(),
        propagateEnv(),
        jvmWorker().internalWorker(),
        discoveredClassesOpt = aheadOfTimeDiscoveredTestClassesIfNeeded(),
        testBatchFrameworkTasks = testBatchFrameworkTasks()
      ).runTests()
    }

    private def diffImageDirPath: Task.Simple[PathRef] = Task {
      val diffImageDir = Task.dest / "diff-images"
      PathRef(diffImageDir)
    }

    override def forkArgs: T[Seq[String]] = super.forkArgs() ++ testJvmArgs()
    override def runClasspath: T[Seq[PathRef]] =
      androidScreenshotRuntimeClasspath() ++ androidPreviewScreenshotTestEngineClasspath() ++
        compileClasspath()

    /** The runtime classpath of the previews, without the test engine itself */
    private def androidScreenshotRuntimeClasspath: T[Seq[PathRef]] = Task {
      super.runClasspath()
    }

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

    /**
     * The inputs of the preview screenshot test engine, passed as system properties. See
     * [[https://android.googlesource.com/platform/tools/base/+/ed71a44af8ab9dc001006ead1fcfe578466b32a9/preview/screenshot/screenshot-validation-junit-engine/src/main/java/com/android/tools/screenshot/PreviewScreenshotTestEngineInput.kt]]
     */
    private def testJvmArgs: T[Seq[String]] = Task {
      def paths(ps: Seq[os.Path]) = ps.distinct.mkString(java.io.File.pathSeparator)
      val mainClasses = outer.compile().classes.path
      val testClasses = compile().classes.path
      val rClassJar = androidProcessedResources().path
      // as AGP does, the dependencies of the previews are the runtime and the compile classpath,
      // plus the R class jar
      val testRuntime =
        androidScreenshotRuntimeClasspath().map(_.path) ++ compileClasspath().map(_.path) :+
          rClassJar
      // as AGP does, the fonts are only passed if the SDK has them
      val fontsPath = Some(androidSdkModule().fontsPath()).filter(os.exists)
      val params = Map(
        "screenshotTestDirectory" -> paths(Seq(testClasses)),
        "mainDirectory" -> paths(Seq(mainClasses)),
        "dependencyJars" -> paths(testRuntime.filter(p => os.isFile(p) && p.ext == "jar")),
        "previewImageOutputDir" -> (androidScreenshotTestResultDir().path / "rendered").toString,
        "previewDiffImageOutputDir" -> diffImageDirPath().path.toString,
        "referenceImageDir" -> androidScreenshotReferenceImageDir().path.toString,
        "projectRoot" -> BuildCtx.workspaceRoot.toString,
        "ImageDiffer.threshold" -> androidScreenshotTestDiffThreshold.toString,
        "Renderer.fontsPath" -> fontsPath.fold("")(_.toString),
        "Renderer.resourceApkPath" -> androidLinkedResources().apk.path.toString,
        "Renderer.namespace" -> androidApplicationNamespace(),
        "Renderer.mainAllClassPath" -> paths(outer.runClasspath().map(_.path)),
        "Renderer.mainProjectClassPath" -> paths(Seq(mainClasses)),
        "Renderer.screenshotAllClassPath" -> paths(testRuntime),
        "Renderer.screenshotProjectClassPath" -> paths(Seq(testClasses)),
        "Renderer.layoutlibDataDir" -> layoutLibRuntimePath().path.toString,
        // the renderer bootstraps layoutlib in an isolated classloader built from these
        "Renderer.layoutlibClassPath" -> paths(
          (composePreviewRenderer() ++ layoutLibRenderer() :+ androidSdkModule().androidJarPath())
            .map(_.path)
        ),
        "Renderer.testRuntimeResourceDirs" -> paths(androidLibraryResources().map(_.path)),
        "Renderer.testRuntimeRClassJars" -> paths(Seq(rClassJar))
      )

      params.map { case (k, v) => s"$JvmTestArg$k=$v" }.toSeq
    }

    private val JvmTestArg = "-DPreviewScreenshotTestEngineInput."

  }

}
