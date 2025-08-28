package mill.kotlinlib.ksp

/**
 * Sets up the kotlin compiler for using KSP 2.x (Kotlin Symbol Processing)
 * This module is based on KSP 2.x which supports language version 2.0 and later.
 * For KSP 1.x, use [[KspModule]] instead.
 *
 *  Documentation: https://github.com/google/ksp/blob/main/docs/ksp2cmdline.md
 */
@mill.api.experimental
trait Ksp2Module extends KspBaseModule { outer =>

  def kspModuleMode: KspModuleMode = KspModuleMode.Ksp2Cli

  type Ksp2Tests = KspTests

}
