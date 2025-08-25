package mill.kotlinlib.ksp

import mill.*
import mill.api.{PathRef, Task}
import mill.kotlinlib.{Dep, KotlinModule}

/**
 * Base trait for KSP (Kotlin Symbol Processing) modules.
 * This trait defines the common interface that both KspModule and Ksp2Module implement.
 */
@mill.api.experimental
trait KspBaseModule extends KotlinModule {

  def kspLanguageVersion: T[String]

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

  /**
   * The classpath when running Kotlin Symbol processing.
   */
  def kspClasspath: T[Seq[PathRef]]

  /**
   * Processor options to be passed to KSP.
   */
  def kspProcessorOptions: T[Map[String, String]] = Task {
    Map.empty[String, String]
  }

  /**
   * Generated sources from KSP processing.
   */
  def generatedSourcesWithKSP: T[GeneratedKSPSources]

  override def generatedSources: T[Seq[PathRef]] = Task {
    super.generatedSources() ++ generatedSourcesWithKSP().sources
  }
}
