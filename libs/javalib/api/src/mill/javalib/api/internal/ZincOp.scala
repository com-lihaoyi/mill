package mill.javalib.api.internal

import mill.api.PathRef
import mill.javalib.api.CompilationResult
import mill.api.JsonFormatters.*
import mill.api.daemon.Result

sealed trait ZincOp extends mill.rpc.MillRpcChannel.Message derives upickle.ReadWriter
object ZincOp {

  /** Compiles Java-only sources. */
  case class CompileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javacOptions: Seq[String],
      incrementalCompilation: Boolean,
      workDir: os.Path
  ) extends ZincOp {
    type Response = Result[CompilationResult]
  }

  /** Compiles Java and Scala sources. */
  case class CompileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String],
      workDir: os.Path
  ) extends ZincOp {
    type Response = Result[CompilationResult]
  }

  /** Creates a Scaladoc jar. */
  case class ScaladocJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      args: Seq[String],
      workDir: os.Path
  ) extends ZincOp {
    type Response = Boolean
  }

  case class DiscoverTests(runCp: Seq[os.Path], testCp: Seq[os.Path], framework: String)
      extends ZincOp {
    type Response = Seq[String]
  }
  case class GetTestTasks(
      runCp: Seq[os.Path],
      testCp: Seq[os.Path],
      framework: String,
      selectors: Seq[String],
      args: Seq[String]
  ) extends ZincOp {
    type Response = Seq[String]
  }

  case class DiscoverJunit5Tests(
      runCp: Seq[os.Path],
      testCp: Seq[os.Path],
      classesDir: Option[os.Path]
  ) extends ZincOp {
    type Response = Seq[String]
  }
}
