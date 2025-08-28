package mill.kotlinlib.ksp

/**
 * Sets up the kotlin compiler for using KSP (Kotlin Symbol Processing)
 * by plugging in the symbol-processing and symbol-processing-api dependencies.
 *
 * Use of kotlin-compiler-embedded is also recommended (and thus enabled by default)
 * to avoid any classpath conflicts between the compiler and user defined plugins!
 *
 * This module is based on KSP 1.x which relies on language version 1.9 or earlier.
 * For KSP 2.x, use [[Ksp2Module]] instead.
 */
@mill.api.experimental
trait KspModule extends KspBaseModule {

  def kspModuleMode: KspModuleMode = KspModuleMode.Ksp1

}
