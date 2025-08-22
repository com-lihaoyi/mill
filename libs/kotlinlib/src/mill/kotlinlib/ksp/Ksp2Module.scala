package mill.kotlinlib.ksp

import coursier.core.VariantSelector.VariantMatcher
import coursier.params.ResolutionParams
import mill.*
import mill.api.Result
import mill.api.{PathRef, Task}
import mill.kotlinlib.{Dep, DepSyntax}
import mill.util.Jvm

import java.io.File

/**
 * Sets up the kotlin compiler for using KSP 2.x (Kotlin Symbol Processing)
 * This module is based on KSP 2.x which supports language version 2.0 and later.
 * For KSP 1.x, use [[KspModule]] instead.
 *
 *  Documentation: https://github.com/google/ksp/blob/main/docs/ksp2cmdline.md
 */
@mill.api.experimental
trait Ksp2Module extends KspBaseModule { outer =>

  def kspVersion: T[String] = "2.0.2"
  def kspJvmTarget: T[String] = "11"

  def kspLanguageVersion: T[String] = "2.0"

  def kspApiVersion: T[String] = "2.0"

  def kspDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"com.google.devtools.ksp:symbol-processing-aa-embeddable:${kotlinVersion()}-${kspVersion()}",
      mvn"com.google.devtools.ksp:symbol-processing-api:${kotlinVersion()}-${kspVersion()}",
      mvn"com.google.devtools.ksp:symbol-processing-common-deps:${kotlinVersion()}-${kspVersion()}",
      mvn"org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2"
    )
  }

  def kspLibraries: T[Seq[PathRef]] = compileClasspath()

  def kspModuleName = moduleSegments.render

  def kspClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(kspDeps(), resolutionParamsMapOpt = Some(addJvmVariantAttributes))
  }

  private[mill] def addJvmVariantAttributes: ResolutionParams => ResolutionParams = { params =>
    params.addVariantAttributes(
      "org.jetbrains.kotlin.platform.type" -> VariantMatcher.Equals("jvm"),
      "org.gradle.jvm.environment" -> VariantMatcher.Equals("standard-jvm")
    )
  }

  def kotlinSymbolProcessorsResolved: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      kotlinSymbolProcessors()
    )
  }

  def kspArgs: T[Seq[String]] = Task { Seq.empty[String] }

  def kspFriendPaths: T[Seq[PathRef]] = Task {
    val compiledCodePaths = Task.traverse(transitiveModuleCompileModuleDeps)(m =>
      Task.Anon {
        Seq(PathRef(m.compile().classes.path))
      }
    )().flatten

    compiledCodePaths
  }

  /**
   * The Kotlin compile task with KSP.
   * This task should run as part of the [[generatedSources]] task to
   * so that the generated  sources are in the [[compileClasspath]]
   * for the main compile task.
   */
  def generatedSourcesWithKSP: T[GeneratedKSPSources] = Task {

    val processorResolvedClasspath = kotlinSymbolProcessorsResolved().map(_.path)
    val processorClasspath = processorResolvedClasspath.mkString(File.pathSeparator)

    val kspOutputDir = Task.dest / "generated"
    val java = kspOutputDir / "java"
    val kotlin = kspOutputDir / "kotlin"
    val resources = kspOutputDir / "resources"
    val classes = kspOutputDir / "classes"
    val kspCachesDir = Task.dest / "caches"

    val processorOptionsValue =
      kspProcessorOptions().map((key, value) => s"$key=$value").toSeq.mkString(File.pathSeparator)

    val processorOptions = if (processorOptionsValue.isEmpty)
      ""
    else
      s"-processor-options=${processorOptionsValue}"
    val args = Seq(
      s"-module-name=${kspModuleName}",
      "-jvm-target",
      kspJvmTarget(),
      s"-jdk-home=${System.getProperty("java.home")}",
      s"-source-roots=${sources().map(_.path).mkString(File.pathSeparator)}",
      s"-project-base-dir=${moduleDir.toString}",
      s"-output-base-dir=${kspOutputDir}",
      s"-caches-dir=${kspCachesDir}",
      s"-libraries=${kspLibraries().map(_.path).mkString(File.pathSeparator)}",
      s"-class-output-dir=${classes}",
      s"-kotlin-output-dir=${kotlin}",
      s"-java-output-dir=${java}",
      s"-resource-output-dir=${resources}",
      s"-language-version=${kspLanguageVersion()}",
      s"-incremental=true",
      s"-incremental-log=true",
      s"-api-version=${kspApiVersion()}",
      processorOptions,
      s"-map-annotation-arguments-in-java=false"
    ) ++ kspArgs() :+ processorClasspath

    val classpath = kspClasspath().map(_.path)
    val mainClass = "com.google.devtools.ksp.cmdline.KSPJvmMain"
    Task.log.info(
      s"Running Kotlin Symbol Processing with java -cp ${classpath.mkString(File.pathSeparator)} ${mainClass} ${args.mkString(" ")}"
    )

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
    override def kspVersion: T[String] = outer.kspVersion()
    override def kspJvmTarget: T[String] = outer.kspJvmTarget()
    override def kotlinVersion: T[String] = outer.kotlinVersion()
    override def kspLanguageVersion: T[String] = outer.kspLanguageVersion()

    override def kspApiVersion: T[String] = outer.kspApiVersion()
  }
}
