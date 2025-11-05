package mill.javalib.api.internal

import mill.api.PathRef
import mill.javalib.api.CompilationResult
import mill.api.JsonFormatters.*
import mill.api.daemon.Result

sealed trait ZincOperation extends mill.rpc.MillRpcMessage derives upickle.ReadWriter {}

/** Compiles Java-only sources. */
case class ZincCompileJava(
    upstreamCompileOutput: Seq[CompilationResult],
    sources: Seq[os.Path],
    compileClasspath: Seq[os.Path],
    javacOptions: JavaCompilerOptions,
    incrementalCompilation: Boolean
) extends ZincOperation {
  type Response = Result[CompilationResult]
}

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
    auxiliaryClassFileExtensions: Seq[String]
) extends ZincOperation {
  type Response = Result[CompilationResult]
}

/** Creates a Scaladoc jar. */
case class ZincScaladocJar(
    scalaVersion: String,
    scalaOrganization: String,
    compilerClasspath: Seq[PathRef],
    scalacPluginClasspath: Seq[PathRef],
    args: Seq[String]
) extends ZincOperation {

  type Response = Boolean
}

case class ZincDiscoverTests(runCp: Seq[os.Path], testCp: Seq[os.Path], framework: String)
    extends ZincOperation {
  type Response = Seq[String]
}
case class ZincGetTestTasks(
    runCp: Seq[os.Path],
    testCp: Seq[os.Path],
    framework: String,
    selectors: Seq[String],
    args: Seq[String]
) extends ZincOperation { type Response = Seq[String] }

case class ZincDiscoverJunit5Tests(
    runCp: Seq[os.Path],
    testCp: Seq[os.Path],
    classesDir: Option[os.Path]
) extends ZincOperation { type Response = Seq[String] }
