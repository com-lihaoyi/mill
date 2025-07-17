package mill.javalib.worker

import mill.util.CachedFactory
import mill.api.*
import mill.api.daemon.*
import mill.api.daemon.internal.internal
import mill.api.daemon.internal.CompileProblemReporter
import mill.api.PathRef
import mill.constants.CodeGenConstants
import mill.javalib.api.{CompilationResult, JvmWorkerApi, JvmWorkerUtil, Versions}
import mill.javalib.internal.JvmWorkerArgs
import mill.javalib.zinc.ZincWorker
import mill.util.RefCountedClassLoaderCache
import sbt.internal.inc.{CompileFailed, FreshCompilerCache, ManagedLoggedReporter, MappedFileConverter, ScalaInstance, Stamps, ZincUtil, javac}
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.internal.inc.consistent.ConsistentFileAnalysisStore
import sbt.internal.util.{ConsoleAppender, ConsoleOut}
import sbt.mill.SbtLoggerUtils
import xsbti.compile.analysis.ReadWriteMappers
import xsbti.compile.{AnalysisContents, AnalysisStore, AuxiliaryClassFileExtension, ClasspathOptions, CompileAnalysis, CompileOrder, Compilers, IncOptions, JavaTools, MiniSetup, PreviousResult}
import xsbti.{PathBasedFile, VirtualFile}
import xsbti.compile.CompileProgress

import java.nio.charset.StandardCharsets
import java.io.File
import java.net.URLClassLoader
import java.util.Optional
import scala.collection.mutable
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Properties.isWin

@internal
class JvmWorkerImpl(args: JvmWorkerArgs) extends JvmWorkerApi with AutoCloseable {
  import args.*

  private val zincLocalWorker =
    ZincWorker(compilerBridge, jobs = jobs, compileToJar = compileToJar, zincLogDebug = zincLogDebug)

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[os.Path],
      args: Seq[String]
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    zincLocalWorker.docJar(
      scalaVersion = scalaVersion, scalaOrganization = scalaOrganization, compilerClasspath = compilerClasspath, scalacPluginClasspath = scalacPluginClasspath, args = args
    )
  }

  override def compileJava(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    val jOpts = JavaCompilerOptions(javacOptions)
    zincLocalWorker.compileJava(
      upstreamCompileOutput = upstreamCompileOutput, sources = sources, compileClasspath = compileClasspath, javacOptions = jOpts.compiler, reporter = reporter,
      reportCachedProblems = reportCachedProblems, incrementalCompilation = incrementalCompilation
    )
  }

  override def compileMixed(
      upstreamCompileOutput: Seq[CompilationResult],
      sources: Seq[os.Path],
      compileClasspath: Seq[os.Path],
      javaHome: Option[os.Path],
      javacOptions: Seq[String],
      scalaVersion: String,
      scalaOrganization: String,
      scalacOptions: Seq[String],
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      reporter: Option[CompileProblemReporter],
      reportCachedProblems: Boolean,
      incrementalCompilation: Boolean,
      auxiliaryClassFileExtensions: Seq[String]
  )(implicit ctx: JvmWorkerApi.Ctx): Result[CompilationResult] = {
    val jOpts = JavaCompilerOptions(javacOptions)
    zincLocalWorker.compileMixed(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = sources,
      compileClasspath = compileClasspath,
      javacOptions = jOpts.compiler,
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      scalacOptions = scalacOptions,
      compilerClasspath = compilerClasspath,
      scalacPluginClasspath = scalacPluginClasspath,
      reporter = reporter,
      reportCachedProblems = reportCachedProblems,
      incrementalCompilation = incrementalCompilation,
      auxiliaryClassFileExtensions = auxiliaryClassFileExtensions,
    )
  }

  override def close(): Unit = {
    close0()
    zincLocalWorker.close()
    // TODO review: close the subprocesses
  }
}
