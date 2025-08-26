package mill.kotlinlib.ksp

import coursier.core.VariantSelector.VariantMatcher
import coursier.params.ResolutionParams
import mill.*
import mill.api.{PathRef, Task}
import mill.kotlinlib.{Dep, KotlinModule}

/**
 * Base trait for KSP (Kotlin Symbol Processing) modules.
 * This trait defines the common interface that both KspModule and Ksp2Module implement.
 */
@mill.api.experimental
trait KspBaseModule extends KotlinModule {

  /**
   * The version of the Kotlin language to use for the KSP stage.
   * For KSP 1.x, this should be 1.9 or earlier.
   * For KSP 2.x, this should be 2.0 or later.
   */
  def kspLanguageVersion: T[String]

  /**
   * The version of the symbol processing library to use, which needs to be compatible with
   * the Kotlin version used.
   *
   * For finding the right versions, also see [[https://github.com/google/ksp/releases]]
   *
   * @return
   */
  def kspVersion: T[String]

  /**
   * The symbol processors to be used by the Kotlin compiler.
   * Default is empty.
   */
  def kotlinSymbolProcessors: T[Seq[Dep]] = Task {
    Seq.empty[Dep]
  }

  /**
   * Resolved classpath for the symbol processors.
   */
  def kotlinSymbolProcessorsResolved: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      kotlinSymbolProcessors()
    )
  }

  private[mill] def addJvmVariantAttributes: ResolutionParams => ResolutionParams = { params =>
    params.addVariantAttributes(
      "org.jetbrains.kotlin.platform.type" -> VariantMatcher.Equals("jvm"),
      "org.gradle.jvm.environment" -> VariantMatcher.Equals("standard-jvm")
    )
  }

  /**
   * The classpath when running Kotlin Symbol processing. Default is this module's compile classpath.
   */
  def kspClasspath: T[Seq[PathRef]] = compileClasspath()

  /**
   * Processor options to be passed to KSP.
   */
  def kspProcessorOptions: T[Map[String, String]] = Task {
    Map.empty[String, String]
  }

  /**
   * The module name to be used by KSP.
   * Default is the same as the module name of this module.
   */
  def kspModuleName = moduleSegments.render

  /**
   * Generated sources from KSP processing.
   */
  def generatedSourcesWithKSP: T[GeneratedKSPSources]

  override def generatedSources: T[Seq[PathRef]] = Task {
    super.generatedSources() ++ generatedSourcesWithKSP().sources
  }
}
