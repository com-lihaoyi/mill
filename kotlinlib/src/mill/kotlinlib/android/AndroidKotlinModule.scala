package mill.kotlinlib.android

import mill.{Agg, T, Task}
import mill.api.PathRef
import mill.javalib.android.AndroidLibModule
import mill.kotlinlib.{DepSyntax, KotlinModule}

trait AndroidKotlinModule extends KotlinModule {

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

  def composeProcessor = Task {
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
}
