package mill.kotlinlib.android
import mill.{Agg, T, Task}
import mill.api.PathRef
import mill.javalib.android.AndroidLibModule
import mill.kotlinlib.{DepSyntax, KotlinModule}

trait AndroidLibKotlinModule extends AndroidLibModule with KotlinModule { outer =>
  override def sources: T[Seq[PathRef]] =
    super[AndroidLibModule].sources() :+ PathRef(moduleDir / "src/main/kotlin")
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
  trait AndroidLibKotlinTests extends AndroidLibTests with KotlinTests {
    override def sources: T[Seq[PathRef]] =
      super[AndroidLibTests].sources() ++ Seq(PathRef(outer.moduleDir / "src/test/kotlin"))
  }
  //    trait AndroidLibKotlinInstrumentedTests extends AndroidLibKotlinModule with AndroidLibInstrumentedTests {
  //
  //      override final def kotlinVersion = outer.kotlinVersion
  //      override final def androidSdkModule = outer.androidSdkModule
  //
  //      override def sources: T[Seq[PathRef]] =
  //        super[AndroidLibInstrumentedTests].sources() :+ PathRef(
  //          outer.millSourcePath / "src/androidTest/kotlin"
  //        )
  //    }
}
