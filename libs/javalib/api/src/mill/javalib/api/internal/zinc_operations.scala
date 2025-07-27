package mill.javalib.api.internal

import mill.api.PathRef
import mill.javalib.api.CompilationResult

/** Compiles Java-only sources. */
case class ZincCompileJava(
  upstreamCompileOutput: Seq[CompilationResult],
  sources: Seq[os.Path],
  compileClasspath: Seq[os.Path],
  javacOptions: JavaCompilerOptions,
  incrementalCompilation: Boolean,
)

/** Compiles Java and Scala sources. */
case class ZincCompileMixed(
  upstreamCompileOutput: Seq[CompilationResult],
  sources: Seq[os.Path],
  compileClasspath: Seq[os.Path],
  javacOptions: JavaCompilerOptions,
  scalaVersion: String,
  scalaOrganization: String,
  scalacOptions: Seq[String],
  compilerClasspath: Seq[PathRef],
  scalacPluginClasspath: Seq[PathRef],
  incrementalCompilation: Boolean,
  auxiliaryClassFileExtensions: Seq[String],
)

/** Creates a Scaladoc jar. */
case class ZincScaladocJar(
  scalaVersion: String,
  scalaOrganization: String,
  compilerClasspath: Seq[PathRef],
  scalacPluginClasspath: Seq[PathRef],
  args: Seq[String]
)
