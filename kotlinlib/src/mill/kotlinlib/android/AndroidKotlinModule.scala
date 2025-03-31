package mill.kotlinlib.android

import mill.{T, Task}
import mill.api.Result
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule}

// TODO expose Compose configuration options
// https://kotlinlang.org/docs/compose-compiler-options.html possible options
trait AndroidKotlinModule extends KotlinModule {

  override def kotlincOptions = super.kotlincOptions() ++ {
    if (androidEnableCompose()) {
      Seq(
        // TODO expose Compose configuration options
        // https://kotlinlang.org/docs/compose-compiler-options.html possible options
        s"-Xplugin=${kotlincPluginJars().map(_.path).mkString(",")}",
        "-P",
        "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaClasses=false," +
          "plugin:androidx.compose.compiler.plugins.kotlin:traceMarkersEnabled=true",
        "-verbose",
        "-opt-in=kotlin.RequiresOptIn",
        "-opt-in=kotlin.Experimental",
        "-Xallow-unstable-dependencies",
      )
    } else Seq.empty
  }

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
        Result.Failure("Compose can be used only with Kotlin version 2 or newer.")
      } else {
        Result.Success(deps ++ Seq(
          mvn"org.jetbrains.kotlin:kotlin-compose-compiler-plugin:${kotlinVersion()}"
        ))
      }
    } else Result.Success(deps)
  }
}
