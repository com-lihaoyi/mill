package mill.androidlib

import mill.api.{PathRef, Result}
import mill.javalib.{CoursierModule, Dep}
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule}
import mill.{T, Task}

// TODO expose Compose configuration options
// https://kotlinlang.org/docs/compose-compiler-options.html possible options
trait AndroidKotlinModule extends KotlinModule with AndroidModule { outer =>

  /**
   * Enable Jetpack Compose support in the module. Default is `false`.
   */
  def androidEnableCompose: T[Boolean] = false

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
    override def kotlinVersion: T[String] = outer.kotlinVersion

    private def kotlinSources = Task.Sources("src/test/kotlin")

    override def sources: T[Seq[PathRef]] =
      super.sources() ++ kotlinSources()

    override def kotlincPluginMvnDeps: T[Seq[Dep]] = outer.kotlincPluginMvnDeps()
  }
}
