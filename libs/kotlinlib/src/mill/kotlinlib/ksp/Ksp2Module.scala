package mill.kotlinlib.ksp

import mill.*
import mill.api.Result
import mill.api.{PathRef, Task}
import mill.kotlinlib.worker.api.KotlinWorkerTarget
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule, KotlinWorkerManager}
import mill.util.Jvm

import java.io.File

/**
 * Sets up the kotlin compiler for using KSP (Kotlin Symbol Processing)
 * by plugging in the symbol-processing and symbol-processing-api dependencies.
 *
 * Use of kotlin-compiler-embedded is also recommended (and thus enabled by default)
 * to avoid any classpath conflicts between the compiler and user defined plugins!
 */
@mill.api.experimental
trait Ksp2Module extends KotlinModule { outer =>

  def kspVersion: T[String] = "2.0.2"
  def kspJvmTarget: T[String] = "11"

  def kspLanguageVersion = "2.0"
  def kspApiVersion = "2.0"

  def kspDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"com.google.devtools.ksp:symbol-processing-aa-embeddable:${kotlinVersion()}-${kspVersion()}",
      mvn"com.google.devtools.ksp:symbol-processing-api:${kotlinVersion()}-${kspVersion()}",
      mvn"com.google.devtools.ksp:symbol-processing-common-deps:${kotlinVersion()}-${kspVersion()}",
      mvn"org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion()}",
      mvn"org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"
    )
  }

  def kspClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(kspDeps())
  }

  def kspProcessorOptions: T[Map[String, String]] = Task {
    Map.empty[String, String]
  }

  /**
   * The symbol processors to be used by the Kotlin compiler.
   * Default is empty.
   */
  def kotlinSymbolProcessors: T[Seq[Dep]] = Task {
    Seq.empty[Dep]
  }

  def kotlinSymbolProcessorsResolved: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      kotlinSymbolProcessors()
    )
  }

  def kspArgs: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * The Kotlin compile task with KSP.
   * This task should run as part of the [[generatedSources]] task to
   * so that the generated  sources are in the [[compileClasspath]]
   * for the main compile task.
   */
  def generatedSourcesWithKSP: T[GeneratedKSPSources] = Task {

    val processorClasspath = (kotlinSymbolProcessorsResolved() ++ compileClasspath()).map(_.path).mkString(File.pathSeparator)

    val kspProjectBasedDir = moduleSegments.render

    val kspOutputDir = Task.dest / "generated"
    val java = kspOutputDir / "java"
    val kotlin = kspOutputDir / "kotlin"
    val resources = kspOutputDir / "resources"
    val classes = kspOutputDir / "classes"
    val kspCachesDir = Task.dest / "caches"

    val processorOptionsValue =
      kspProcessorOptions().map((key,value) => s"$key=$value").toSeq.mkString(":")

    val processorOptions = if (processorOptionsValue.isEmpty)
      ""
    else
      s"-processor-options=${processorOptionsValue}"
    val args = Seq(
      s"-module-name=${kspProjectBasedDir}",
      "-jvm-target", kspJvmTarget(),
      s"-source-roots=${moduleDir}/src/main",
      s"-project-base-dir=${moduleDir.toString}",
      s"-output-base-dir=${kspOutputDir}",
      s"-caches-dir=${kspCachesDir}",
      s"-class-output-dir=${classes}",
      s"-kotlin-output-dir=${kotlin}",
      s"-java-output-dir=${java}",
      s"-resource-output-dir=${resources}",
      s"-language-version=${kspLanguageVersion}",
      s"-incremental=true",
      s"-api-version=${kspApiVersion}",
      processorOptions,
      s"-map-annotation-arguments-in-java=false",
    ) ++ kspArgs() :+ processorClasspath

    val classpath = (kspClasspath() ++ compileClasspath()).map(_.path)
    val mainClass = "com.google.devtools.ksp.cmdline.KSPJvmMain"
    Task.log.info(
      s"Running Kotlin Symbol Processing with java -cp ${classpath.mkString(File.pathSeparator)} ${mainClass} ${args.mkString(" ")}"
    )

    val compiledSources = Task.dest / "compiled"
    os.makeDir.all(compiledSources)

    val jvmCall = Jvm.callProcess(
      mainClass = mainClass,
      classPath = classpath,
      mainArgs = args
    )

    Task.log.info(
      s"KSP finished with exit code: ${jvmCall.exitCode}"
    )

    Task.log.debug(
      s"KSP output: ${jvmCall.out.text()}"
    )

    GeneratedKSPSources(PathRef(java), PathRef(kotlin), PathRef(resources), PathRef(classes))
  }

  /**
   * A test sub-module linked to its parent module best suited for unit-tests.
   */
  trait Ksp2Tests extends Ksp2Module with KotlinTests {
    override def kspVersion: T[String] = outer.kspVersion
  }
}
