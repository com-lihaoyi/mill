package mill.javalib.worker

import mill.util.CachedFactory
import mill.api._
import mill.api.daemon._
import mill.api.daemon.internal.internal
import mill.api.daemon.internal.CompileProblemReporter
import mill.api.PathRef
import mill.constants.CodeGenConstants
import mill.javalib.api.{CompilationResult, Versions, JvmWorkerApi, JvmWorkerUtil}
import mill.util.RefCountedClassLoaderCache
import sbt.internal.inc.{
  CompileFailed,
  FreshCompilerCache,
  ManagedLoggedReporter,
  MappedFileConverter,
  ScalaInstance,
  Stamps,
  ZincUtil,
  javac
}
import sbt.internal.inc.classpath.ClasspathUtil
import sbt.internal.inc.consistent.ConsistentFileAnalysisStore
import sbt.internal.util.{ConsoleAppender, ConsoleOut}
import sbt.mill.SbtLoggerUtils
import xsbti.compile.analysis.ReadWriteMappers
import xsbti.compile.{
  AnalysisContents,
  AnalysisStore,
  AuxiliaryClassFileExtension,
  ClasspathOptions,
  CompileAnalysis,
  CompileOrder,
  Compilers,
  IncOptions,
  JavaTools,
  MiniSetup,
  PreviousResult
}
import xsbti.{PathBasedFile, VirtualFile}
import xsbti.compile.CompileProgress

import java.nio.charset.StandardCharsets
import java.io.File
import java.net.URLClassLoader
import java.util.Optional
import scala.collection.mutable
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Properties.isWin

//noinspection ScalaUnusedSymbol - used dynamically by classloading via a FQCN
@internal
class JvmWorkerImpl(
    compilerBridge: Either[
      (JvmWorkerApi.Ctx, (String, String) => (Option[Seq[PathRef]], PathRef)),
      String => PathRef
    ],
    jobs: Int,
    compileToJar: Boolean,
    zincLogDebug: Boolean,
    close0: () => Unit
) extends JvmWorkerApi with AutoCloseable {

  def docJar(
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[os.Path],
      args: Seq[String]
  )(using ctx: JvmWorkerApi.Ctx): Boolean = {
    withScalaCompilers(
      scalaVersion,
      scalaOrganization,
      compilerClasspath,
      scalacPluginClasspath,
      javaHome,
      Seq()
    ) { compilers =>
      // Not sure why dotty scaladoc is flaky, but add retries to workaround it
      // https://github.com/com-lihaoyi/mill/issues/4556
      mill.util.Retry(count = 2) {
        if (JvmWorkerUtil.isDotty(scalaVersion) || JvmWorkerUtil.isScala3Milestone(scalaVersion)) {
          // dotty 0.x and scala 3 milestones use the dotty-doc tool
          val dottydocClass =
            compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.dottydoc.DocDriver")
          val dottydocMethod = dottydocClass.getMethod("process", classOf[Array[String]])
          val reporter =
            dottydocMethod.invoke(dottydocClass.getConstructor().newInstance(), args.toArray)
          val hasErrorsMethod = reporter.getClass.getMethod("hasErrors")
          !hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
        } else if (JvmWorkerUtil.isScala3(scalaVersion)) {
          // DottyDoc makes use of `com.fasterxml.jackson.databind.Module` which
          // requires the ContextClassLoader to be set appropriately
          mill.api.ClassLoader.withContextClassLoader(getClass.getClassLoader) {

            val scaladocClass =
              compilers.scalac().scalaInstance().loader().loadClass("dotty.tools.scaladoc.Main")

            val scaladocMethod = scaladocClass.getMethod("run", classOf[Array[String]])
            val reporter =
              scaladocMethod.invoke(scaladocClass.getConstructor().newInstance(), args.toArray)
            val hasErrorsMethod = reporter.getClass.getMethod("hasErrors")
            !hasErrorsMethod.invoke(reporter).asInstanceOf[Boolean]
          }
        } else {
          val scaladocClass =
            compilers.scalac().scalaInstance().loader().loadClass("scala.tools.nsc.ScalaDoc")
          val scaladocMethod = scaladocClass.getMethod("process", classOf[Array[String]])
          scaladocMethod.invoke(
            scaladocClass.getConstructor().newInstance(),
            args.toArray
          ).asInstanceOf[Boolean]
        }
      }
    }
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
    val cacheKey = JavaCompilerCacheKey(javaHome, javacOptions.filter(filterJavacRuntimeOptions))
    javaOnlyCompilerCache.withValue(cacheKey) { compilers =>
      compileInternal(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = javacOptions,
        scalacOptions = Nil,
        compilers = compilers,
        reporter = reporter,
        reportCachedProblems = reportCachedProblems,
        incrementalCompilation = incrementalCompilation,
        auxiliaryClassFileExtensions = Seq.empty[String]
      )
    }
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
    withScalaCompilers(
      scalaVersion = scalaVersion,
      scalaOrganization = scalaOrganization,
      compilerClasspath = compilerClasspath,
      scalacPluginClasspath = scalacPluginClasspath,
      javaHome = javaHome,
      javacOptions = javacOptions
    ) { compilers =>
      compileInternal(
        upstreamCompileOutput = upstreamCompileOutput,
        sources = sources,
        compileClasspath = compileClasspath,
        javacOptions = javacOptions,
        scalacOptions = scalacOptions,
        compilers = compilers,
        reporter = reporter,
        reportCachedProblems: Boolean,
        incrementalCompilation,
        auxiliaryClassFileExtensions
      )
    }
  }

  private def withScalaCompilers[T](
      scalaVersion: String,
      scalaOrganization: String,
      compilerClasspath: Seq[PathRef],
      scalacPluginClasspath: Seq[PathRef],
      javaHome: Option[os.Path],
      javacOptions: Seq[String]
  )(f: Compilers => T) = {
    val javacRuntimeOptions = javacOptions.filter(filterJavacRuntimeOptions)

    val cacheKey = ScalaCompileCacheKey(
      scalaVersion,
      compilerClasspath,
      scalacPluginClasspath,
      scalaOrganization,
      javaHome,
      javacRuntimeOptions
    )
    scalaCompilerCache.withValue(cacheKey) { cached =>
      f(cached.compilers)
    }
  }

  override def close(): Unit = {
    close0()
    // TODO review: close the subprocesses
  }
}
