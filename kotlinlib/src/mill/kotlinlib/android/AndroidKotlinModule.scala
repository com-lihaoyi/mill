package mill.kotlinlib.android

import mill.{T, Task}
import mill.api.{PathRef, Result}
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule}

// TODO expose Compose configuration options
// https://kotlinlang.org/docs/compose-compiler-options.html possible options
trait AndroidKotlinModule extends KotlinModule {

  /**
   * Enable Jetpack Compose support in the module. Default is `false`.
   */
  def androidEnableCompose: T[Boolean] = false

  override def kotlinCompilerPluginIvyDeps: T[Seq[Dep]] = Task {
    val kv = kotlinVersion()

    val deps = super.kotlinCompilerPluginIvyDeps()

    if (androidEnableCompose()) {
      if (kv.startsWith("1")) {
        // cut-off usages for Kotlin 1.x, because of the need to maintain the table of
        // Compose compiler version -> Kotlin version
        Result.Failure("Compose can be used only with Kotlin version 2 or newer.")
      } else {
        Result.Success(deps ++ Seq(
          ivy"org.jetbrains.kotlin:kotlin-compose-compiler-plugin:${kotlinVersion()}"
        ))
      }
    } else Result.Success(deps)
  }
}
