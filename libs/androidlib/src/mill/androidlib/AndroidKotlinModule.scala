package mill.androidlib

import mill.*
import mill.api.{PathRef, Result, ModuleRef}
import mill.javalib.{CoursierModule, Dep}
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule}
import mill.{T, Task}
import mill.androidlib.databinding.{
  AndroidDataBindingWorker,
  GenerateBindingSourcesArgs,
  ProcessResourcesArgs,
  AndroidDataBindingWorkerModule
}
import mill.util.Jvm

// TODO expose Compose configuration options
// https://kotlinlang.org/docs/compose-compiler-options.html possible options
trait AndroidKotlinModule extends KotlinModule with AndroidModule { outer =>

  /**
   * Enable Jetpack Compose support in the module. Default is `false`.
   */
  def androidEnableCompose: T[Boolean] = false

  /**
   * Enable viewBinding feature (Part of Android Jetpack)
   *
   * For more information go to [[https://developer.android.com/topic/libraries/view-binding]]
   */
  def androidEnableViewBinding: Boolean = false

  /**
   * Enable dataBinding feature (Part of Android Jetpack)
   *
   * For more information go to [[https://developer.android.com/topic/libraries/data-binding]]
   */
  def androidEnableDataBinding: Boolean = false

  private def isBindingEnabled: Boolean = androidEnableViewBinding || androidEnableDataBinding

  def androidDataBindingCompilerVersion: T[String] = Task {
    isBindingEnabled match {
      case true => throw new Exception(
          "androidDataBindingCompilerVersion must be set (e.g. \"8.13.0\") when view or data binding is enabled."
        )
      case false => ""
    }
  }

  def androidDataBindingCompilerDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"androidx.databinding:databinding-compiler:${androidDataBindingCompilerVersion()}",
      mvn"androidx.databinding:databinding-compiler-common:${androidDataBindingCompilerVersion()}"
    )
  }

  def androidDataBindingWorkerModule: ModuleRef[AndroidDataBindingWorkerModule] =
    ModuleRef(AndroidDataBindingWorkerModule)

  def androidDataBindingCompilerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(
        Dep.millProjectModule("mill-libs-androidlib-databinding-impl")
      ) ++ androidDataBindingCompilerDeps()
    )
  }

  def androidDataBindingWorkerClassloader: Worker[ClassLoader] = Task.Worker {
    Jvm.createClassLoader(
      classPath = androidDataBindingCompilerClasspath().map(_.path),
      parent = getClass.getClassLoader
    )
  }

  def androidDataBindingWorker: Worker[AndroidDataBindingWorker] = Task.Worker {
    androidDataBindingWorkerClassloader().loadClass(
      "mill.androidlib.databinding.AndroidDataBindingImpl"
    )
      .getConstructor()
      .newInstance()
      .asInstanceOf[AndroidDataBindingWorker]
  }

  def androidProcessedLayoutXmls: T[PathRef] = Task {

    val resOutputDir = Task.dest / "resources"
    val layoutInfoOutputDir = Task.dest / "layout_info"

    os.makeDir.all(resOutputDir)
    os.makeDir.all(layoutInfoOutputDir)
    val args = ProcessResourcesArgs(
      applicationPackageName = androidNamespace,
      resInputDir = androidResources().head.path.toString,
      resOutputDir = resOutputDir.toString,
      layoutInfoOutputDir = layoutInfoOutputDir.toString,
      enableViewBinding = androidEnableViewBinding,
      enableDataBinding = androidEnableDataBinding
    )

    androidDataBindingWorkerModule().processResources(androidDataBindingWorker(), args)

    PathRef(Task.dest)
  }

  def generatedAndroidBindingSources: T[PathRef] = Task {
    val logDir = Task.dest / "logs"
    val outputDir = Task.dest / "generated"
    val classInfoDir = Task.dest / "class_info"
    os.makeDir.all(logDir)
    os.makeDir.all(outputDir)
    os.makeDir.all(classInfoDir)
    val args = GenerateBindingSourcesArgs(
      applicationPackageName = androidNamespace,
      layoutInfoDir = (androidProcessedLayoutXmls().path / "layout_info").toString,
      classInfoDir = classInfoDir.toString,
      outputDir = outputDir.toString,
      logFolder = logDir.toString,
      enableViewBinding = androidEnableViewBinding,
      enableDataBinding = androidEnableDataBinding
    )

    androidDataBindingWorkerModule().generateBindingSources(androidDataBindingWorker(), args)

    PathRef(Task.dest)
  }

  override def generatedSources: T[Seq[PathRef]] = isBindingEnabled match {
    case true => super.generatedSources() :+ generatedAndroidBindingSources()
    case false => super.generatedSources()
  }

  /**
   * If data binding or view binding is enabled, aapt2 needs the processed resources
   * https://android.googlesource.com/platform/frameworks/data-binding/+/85dd11e6e0da7a35ca0c154beaf02b7f7217bd2f/exec/src/main/java/android/databinding/cli/ProcessXmlOptions.java#39
   */
  override def androidCompiledModuleResources: T[Seq[PathRef]] = isBindingEnabled match {
    case true => Task {
        val moduleResources = Seq(androidProcessedLayoutXmls().path / "resources")

        val aapt2Compile = Seq(androidSdkModule().aapt2Exe().path.toString(), "compile")

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
        androidTransitiveCompiledResources() ++ Seq(PathRef(Task.dest))
      }
    case false => super.androidCompiledModuleResources()
  }

  override def kotlincPluginMvnDeps: T[Seq[Dep]] = Task {
    val kv = kotlinVersion()

    val deps = super.kotlincPluginMvnDeps()

    if (androidEnableCompose()) {
      if (kv.startsWith("1")) {
        // cut-off usages for Kotlin 1.x, because of the need to maintain the table of
        // Compose compiler version -> Kotlin version
        Task.fail("Compose can be used only with Kotlin version 2 or newer.")
      } else {
        if (kotlinUseEmbeddableCompiler())
          deps ++ Seq(
            mvn"org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${kotlinVersion()}"
          )
        else
          deps ++ Seq(
            mvn"org.jetbrains.kotlin:kotlin-compose-compiler-plugin:${kotlinVersion()}"
          )
      }
    } else deps
  }

  /**
   * If this module has any module dependencies, we need to tell the kotlin compiler to
   * handle the compiled output as a friend path so top level declarations are visible.
   */
  def kotlincFriendPaths: T[Option[String]] = Task {
    val compiledCodePaths = Task.traverse(transitiveModuleCompileModuleDeps)(m =>
      Task.Anon {
        Seq(m.compile().classes.path)
      }
    )().flatten

    val friendlyPathFlag: Option[String] =
      compiledCodePaths.headOption.map(_ => s"-Xfriend-paths=${compiledCodePaths.mkString(",")}")

    friendlyPathFlag
  }

  override def kotlincOptions: T[Seq[String]] = Task {
    super.kotlincOptions() ++ kotlincFriendPaths().toSeq
  }

  def kspDependencyResolver: Task[CoursierModule.Resolver] = Task.Anon {
    new CoursierModule.Resolver(
      repositories = repositoriesTask(),
      bind = bindDependency(),
      mapDependencies = Some(mapDependencies()),
      customizer = resolutionCustomizer(),
      coursierCacheCustomizer = coursierCacheCustomizer(),
      resolutionParams = resolutionParams(),
      offline = Task.offline,
      checkGradleModules = false
    )
  }

  override def kotlincPluginJars: T[Seq[PathRef]] = Task {
    val jars = kspDependencyResolver().classpath(
      kotlincPluginMvnDeps()
        // Don't resolve transitive jars
        .map(d => d.exclude("*" -> "*"))
    )
    jars
  }

  trait AndroidKotlinTestModule extends KotlinTests, AndroidTestModule {
    override def outerRef: ModuleRef[AndroidKotlinModule] = ModuleRef(AndroidKotlinModule.this)
    override def kotlinVersion: T[String] = outerRef().kotlinVersion

    private def kotlinSources = Task.Sources("src/test/kotlin")

    override def sources: T[Seq[PathRef]] =
      super.sources() ++ kotlinSources()

    override def kotlincPluginMvnDeps: T[Seq[Dep]] = outerRef().kotlincPluginMvnDeps()
  }
}
