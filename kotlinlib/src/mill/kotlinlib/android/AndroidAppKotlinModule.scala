package mill.kotlinlib.android

import coursier.Dependency
import coursier.core.Reconciliation
import coursier.params.ResolutionParams
import coursier.util.ModuleMatchers
import mill.{Agg, Command, T, Task}
import mill.api.PathRef
import mill.define.ModuleRef
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
    super[AndroidAppModule].sources() :+ PathRef(millSourcePath / "src/main/kotlin")

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
      Agg(
        ivy"org.jetbrains.kotlin:kotlin-compose-compiler-plugin:${kotlinVersion()}"
      )
    ).head
  }

  trait AndroidAppKotlinTests extends AndroidAppTests with KotlinTests {
    override def sources: T[Seq[PathRef]] =
      super[AndroidAppTests].sources() ++ Seq(PathRef(outer.millSourcePath / "src/test/kotlin"))
  }

  trait AndroidAppKotlinInstrumentedTests extends AndroidAppKotlinModule
      with AndroidAppInstrumentedTests {

    override final def kotlinVersion = outer.kotlinVersion
    override final def androidSdkModule = outer.androidSdkModule

    override def sources: T[Seq[PathRef]] =
      super[AndroidAppInstrumentedTests].sources() :+ PathRef(
        outer.millSourcePath / "src/androidTest/kotlin"
      )
  }

  trait AndroidAppKotlinScreenshotTests extends AndroidAppKotlinModule with TestModule
      with Junit5 {

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
        PathRef(outer.millSourcePath / "src/screenshotTest/kotlin"),
        PathRef(outer.millSourcePath / "src/screenshotTest/java")
      )
    )

    def composePreviewRenderer: T[Agg[PathRef]] = Task {
      defaultResolver().resolveDeps(
        Agg(
          ivy"com.android.tools.compose:compose-preview-renderer:$composePreviewRendererVersion"
        )
      )
    }

    def layoutLibRenderer: T[Agg[PathRef]] = Task {
      defaultResolver().resolveDeps(
        Agg(
          ivy"com.android.tools.layoutlib:layoutlib:$layoutLibVersion"
        )
      )
    }

    def layoutLibRuntime: T[Agg[PathRef]] = Task {
      defaultResolver().resolveDeps(
        Agg(
          ivy"com.android.tools.layoutlib:layoutlib-runtime:$layoutLibVersion"
        )
      )
    }

    def layoutLibFrameworkRes: T[Agg[PathRef]] = Task {
      defaultResolver().resolveDeps(
        Agg(
          ivy"com.android.tools.layoutlib:layoutlib-resources:$layoutLibVersion"
        )
      )
    }

    def layoutLibRuntimePath: T[PathRef] = Task {
      val extractDestination = T.dest / "layoutLib"
      val layoutLibJar = layoutLibRuntime().head
      os.unzip(layoutLibJar.path, extractDestination)
      val frameworkResPath = extractDestination / "data" / "framework_res.jar"
      os.copy(from = layoutLibFrameworkRes().head.path, to = frameworkResPath)
      PathRef(extractDestination)
    }

    def uiToolingVersion: String = "1.7.6"

    override def resolutionParams: Task[ResolutionParams] = Task.Anon {
      val params = super.resolutionParams()
      relaxedDependencyReconciliation(params)
    }

    private val relaxedDependencyReconciliation: ResolutionParams => ResolutionParams =
      _.withReconciliation(Seq(ModuleMatchers.all -> Reconciliation.Relaxed))

    override def generatedSources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

    def resourceApkPath: T[PathRef] = Task {
      PathRef(outer.androidResources()._1.path / "res.apk")
    }

    override def mandatoryIvyDeps: T[Agg[Dep]] = super.mandatoryIvyDeps() ++
      Agg(
        ivy"androidx.compose.ui:ui:$uiToolingVersion",
        ivy"androidx.compose.ui:ui-tooling:$uiToolingVersion",
        ivy"androidx.compose.ui:ui-test-manifest:$uiToolingVersion",
        ivy"androidx.compose.ui:ui-tooling-preview-android:$uiToolingVersion"
      )

    def androidScreenshotTestResults: T[PathRef] =
      Task { PathRef(Task.dest / "results.json") }

    private def screenshotResults: Task[PathRef] = Task {
      val dir = T.dest / "generated-screenshots"
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
      val metadataFolder = T.dest / "meta-data"
      val cliArgsFile = T.dest / "cli_arguments.json"
      val resultsFilePath = androidScreenshotTestResults().path

      val cliArgs = ComposeRenderer.Args(
        fontsPath = androidSdkModule().fontsPath().toString,
        layoutlibPath = layoutLibRuntimePath().path.toString(),
        outputFolder = output.toString(),
        metaDataFolder = metadataFolder.toString(),
        classPath = compileClasspath().map(_.path.toString()).toSeq,
        projectClassPath = Seq(compile().classes.path.toString()),
        screenshots = androidConfigurationByScreenshotTest,
        namespace = namespace,
        resourceApkPath = resourceApkPath().path.toString(),
        resultsFilePath = resultsFilePath.toString()
      )
      os.write(cliArgsFile, upickle.default.write(cliArgs))

      PathRef(cliArgsFile)

    }

    /**
     * Invokes the preview renderer similar to
     * [[https://android.googlesource.com/platform/tools/base/+/61923408e5f7dc20f0840844597f9dde17453a0f/preview/screenshot/screenshot-test-gradle-plugin/src/main/java/com/android/compose/screenshot/tasks/PreviewRenderWorkAction.kt]]
     * @return
     */
    def generatePreviews(): Command[Agg[PathRef]] = Task.Command {
      mill.util.Jvm.runSubprocess(
        mainClass = "com.android.tools.render.compose.MainKt",
        classPath = composePreviewRenderer().map(_.path) ++ layoutLibRenderer().map(_.path),
        jvmArgs = Seq(
          "-Dlayoutlib.thread.profile.timeoutms=10000",
          "-Djava.security.manager=allow"
        ),
        mainArgs = Seq(composePreviewArgs().path.toString())
      )

      Agg.from(os.walk(screenshotResults().path).filter(_.ext == "png").map(PathRef(_)))
    }

    def androidScreenshotDeviceConfigurations: Seq[ComposeRenderer.PreviewParams] = Seq(
      ComposeRenderer.PreviewParams(
        device = "spec:id=reference_phone,shape=Normal,width=411,height=891,unit=dp,dpi=420",
        name = "Phone",
        showSystemUi = "true"
      )
    )

    /* TODO we can do method discovery with reflection or junit tools */
    def androidScreenshotTestMethods: Seq[(String, Seq[String])]

    def androidConfigurationByScreenshotTest: Seq[ComposeRenderer.Screenshot] = for {
      (methodFQN, methodParams) <- androidScreenshotTestMethods
      previewParams <- androidScreenshotDeviceConfigurations
    } yield ComposeRenderer.Screenshot(
      methodFQN = methodFQN,
      methodParams = methodParams,
      previewParams = previewParams,
      previewId = s"${methodFQN}_${previewParams.device}"
    )
  }
}
