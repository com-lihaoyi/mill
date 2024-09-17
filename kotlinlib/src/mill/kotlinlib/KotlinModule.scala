/*
 * Copyright 2020-Present Original lefou/mill-kotlin repository contributors.
 */

package mill
package kotlinlib

import mill.api.{PathRef, Result}
import mill.define.{Command, ModuleRef, Task}
import mill.kotlinlib.worker.api.KotlinWorker
import mill.scalalib.api.{CompilationResult, ZincWorkerApi}
import mill.scalalib.{Dep, DepSyntax, JavaModule, Lib, ZincWorkerModule}
import mill.util.Util.millProjectModule
import mill.{Agg, T}

import java.io.File
import mill.define.Target

trait KotlinModule extends JavaModule { outer =>

  /**
   * All individual source files fed into the compiler.
   */
  override def allSourceFiles: Target[Seq[PathRef]] = Task {
    Lib.findSourceFiles(allSources(), Seq("kt", "kts", "java")).map(PathRef(_))
  }

  /**
   * All individual Java source files fed into the compiler.
   * Subset of [[allSourceFiles]].
   */
  def allJavaSourceFiles: Target[Seq[PathRef]] = Task {
    allSourceFiles().filter(_.path.ext.toLowerCase() == "java")
  }

  /**
   * All individual Kotlin source files fed into the compiler.
   * Subset of [[allSourceFiles]].
   */
  def allKotlinSourceFiles: Target[Seq[PathRef]] = Task {
    allSourceFiles().filter(path => Seq("kt", "kts").exists(path.path.ext.toLowerCase() == _))
  }

  /**
   * The Kotlin version to be used (for API and Language level settings).
   */
  def kotlinVersion: T[String]

  /**
   * The dependencies of this module.
   * Defaults to add the kotlin-stdlib dependency matching the [[kotlinVersion]].
   */
  override def mandatoryIvyDeps: T[Agg[Dep]] = Task {
    super.mandatoryIvyDeps() ++ Agg(
      ivy"org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion()}"
    )
  }

  /**
   * The version of the Kotlin compiler to be used.
   * Default is derived from [[kotlinVersion]].
   */
  def kotlinCompilerVersion: T[String] = Task { kotlinVersion() }

  type CompileProblemReporter = mill.api.CompileProblemReporter

  protected def zincWorkerRef: ModuleRef[ZincWorkerModule] = zincWorker

  protected def kotlinWorkerRef: ModuleRef[KotlinWorkerModule] = ModuleRef(KotlinWorkerModule)

  private[kotlinlib] def kotlinWorkerClasspath = Task {
    millProjectModule(
      "mill-kotlinlib-worker-impl",
      repositoriesTask(),
      resolveFilter = _.toString.contains("mill-kotlinlib-worker-impl")
    )
  }

  /**
   * The Java classpath resembling the Kotlin compiler.
   * Default is derived from [[kotlinCompilerIvyDeps]].
   */
  def kotlinCompilerClasspath: T[Seq[PathRef]] = Task {
    resolveDeps(
      T.task { kotlinCompilerIvyDeps().map(bindDependency()) }
    )().toSeq ++ kotlinWorkerClasspath()
  }

  /**
   * The Ivy/Coursier dependencies resembling the Kotlin compiler.
   * Default is derived from [[kotlinCompilerVersion]].
   */
  def kotlinCompilerIvyDeps: T[Agg[Dep]] = Task {
    Agg(ivy"org.jetbrains.kotlin:kotlin-compiler:${kotlinCompilerVersion()}") ++
//      (
//        if (Seq("1.0.", "1.1.", "1.2").exists(prefix => kotlinVersion().startsWith(prefix)))
//          Agg(ivy"org.jetbrains.kotlin:kotlin-runtime:${kotlinCompilerVersion()}")
//        else Seq()
//      ) ++
      (
        if (
          !Seq("1.0.", "1.1.", "1.2.0", "1.2.1", "1.2.2", "1.2.3", "1.2.4").exists(prefix =>
            kotlinVersion().startsWith(prefix)
          )
        )
          Agg(ivy"org.jetbrains.kotlin:kotlin-scripting-compiler:${kotlinCompilerVersion()}")
        else Seq()
      )
//    ivy"org.jetbrains.kotlin:kotlin-scripting-compiler-impl:${kotlinCompilerVersion()}",
//    ivy"org.jetbrains.kotlin:kotlin-scripting-common:${kotlinCompilerVersion()}",
  }

//  @Deprecated("Use kotlinWorkerTask instead, as this does not need to be cached as Worker")
//  def kotlinWorker: Worker[KotlinWorker] = T.worker {
//    kotlinWorkerTask()
//  }

  def kotlinWorkerTask: Task[KotlinWorker] = T.task {
    kotlinWorkerRef().kotlinWorkerManager().get(kotlinCompilerClasspath())
  }

  /**
   * Compiles all the sources to JVM class files.
   */
  override def compile: T[CompilationResult] = Task {
    kotlinCompileTask()()
  }

  /**
   * Runs the Kotlin compiler with the `-help` argument to show you the built-in cmdline help.
   * You might want to add additional arguments like `-X` to see extra help.
   */
  def kotlincHelp(args: String*): Command[Unit] = T.command {
    kotlinCompileTask(Seq("-help") ++ args)()
    ()
  }

  protected def when(cond: Boolean)(args: String*): Seq[String] = if (cond) args else Seq()

  /**
   * The actual Kotlin compile task (used by [[compile]] and [[kotlincHelp]]).
   */
  protected def kotlinCompileTask(extraKotlinArgs: Seq[String] = Seq()): Task[CompilationResult] =
    T.task {
      val ctx = T.ctx()
      val dest = ctx.dest
      val classes = dest / "classes"
      os.makeDir.all(classes)

      val javaSourceFiles = allJavaSourceFiles().map(_.path)
      val kotlinSourceFiles = allKotlinSourceFiles().map(_.path)

      val isKotlin = kotlinSourceFiles.nonEmpty
      val isJava = javaSourceFiles.nonEmpty
      val isMixed = isKotlin && isJava

      val compileCp = compileClasspath().map(_.path).filter(os.exists)
      val updateCompileOutput = upstreamCompileOutput()

      def compileJava: Result[CompilationResult] = {
        ctx.log.info(
          s"Compiling ${javaSourceFiles.size} Java sources to ${classes} ..."
        )
        // The compile step is lazy, but its dependencies are not!
        internalCompileJavaFiles(
          worker = zincWorkerRef().worker(),
          upstreamCompileOutput = updateCompileOutput,
          javaSourceFiles = javaSourceFiles,
          compileCp = compileCp,
          javacOptions = javacOptions(),
          compileProblemReporter = ctx.reporter(hashCode),
          reportOldProblems = internalReportOldProblems()
        )
      }

      if (isMixed || isKotlin) {
        ctx.log.info(
          s"Compiling ${kotlinSourceFiles.size} Kotlin sources to ${classes} ..."
        )
        val compilerArgs: Seq[String] = Seq(
          // destdir
          Seq("-d", classes.toIO.getAbsolutePath()),
          // classpath
          when(compileCp.iterator.nonEmpty)(
            "-classpath",
            compileCp.iterator.mkString(File.pathSeparator)
          ),
          kotlincOptions(),
          extraKotlinArgs,
          // parameters
          (kotlinSourceFiles ++ javaSourceFiles).map(_.toIO.getAbsolutePath())
        ).flatten

        val workerResult = kotlinWorkerTask().compile(compilerArgs: _*)

        val analysisFile = dest / "kotlin.analysis.dummy"
        os.write(target = analysisFile, data = "", createFolders = true)

        workerResult match {
          case Result.Success(_) =>
            val cr = CompilationResult(analysisFile, PathRef(classes))
            if (!isJava) {
              // pure Kotlin project
              cr
            } else {
              // also run Java compiler and use it's returned result
              compileJava
            }
          case Result.Failure(reason, _) =>
            Result.Failure(reason, Some(CompilationResult(analysisFile, PathRef(classes))))
          case e: Result.Exception => e
          case Result.Aborted => Result.Aborted
          case Result.Skipped => Result.Skipped
          //      case x => x
        }
      } else {
        // it's Java only
        compileJava
      }
    }

  /**
   * Additional Kotlin compiler options to be used by [[compile]].
   */
  def kotlincOptions: T[Seq[String]] = Task {
    Seq("-no-stdlib") ++
      when(!kotlinVersion().startsWith("1.0"))(
        "-language-version",
        kotlinVersion().split("[.]", 3).take(2).mkString("."),
        "-api-version",
        kotlinVersion().split("[.]", 3).take(2).mkString(".")
      )
  }

  private[kotlinlib] def internalCompileJavaFiles(
      worker: ZincWorkerApi,
      upstreamCompileOutput: Seq[CompilationResult],
      javaSourceFiles: Seq[os.Path],
      compileCp: Agg[os.Path],
      javacOptions: Seq[String],
      compileProblemReporter: Option[CompileProblemReporter],
      reportOldProblems: Boolean
  )(implicit ctx: ZincWorkerApi.Ctx): Result[CompilationResult] = {
    worker.compileJava(
      upstreamCompileOutput = upstreamCompileOutput,
      sources = javaSourceFiles,
      compileClasspath = compileCp,
      javacOptions = javacOptions,
      reporter = compileProblemReporter,
      reportCachedProblems = reportOldProblems
    )
  }

  private[kotlinlib] def internalReportOldProblems: Task[Boolean] = zincReportCachedProblems

  /**
   * A test sub-module linked to its parent module best suited for unit-tests.
   */
  trait KotlinModuleTests extends JavaModuleTests with KotlinModule {
    override def kotlinVersion: T[String] = Task { outer.kotlinVersion() }
    override def kotlinCompilerVersion: T[String] = Task { outer.kotlinCompilerVersion() }
    override def kotlincOptions: T[Seq[String]] = Task { outer.kotlincOptions() }
    override def defaultCommandName(): String = super.defaultCommandName()
  }

}
