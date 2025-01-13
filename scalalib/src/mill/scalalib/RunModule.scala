package mill.scalalib

import mainargs.arg
import mill.api.JsonFormatters.pathReadWrite
import mill.api.{Ctx, PathRef, Result}
import mill.define.{Command, Task}
import mill.util.Jvm
import mill.{Agg, Args, T}
import os.{Path, ProcessOutput}

import scala.util.control.NonFatal

trait RunModule extends WithZincWorker {

  /**
   * Any command-line parameters you want to pass to the forked JVM.
   */
  def forkArgs: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Any environment variables you want to pass to the forked JVM.
   */
  def forkEnv: T[Map[String, String]] = Task { Map.empty[String, String] }

  def forkWorkingDir: T[os.Path] = Task { T.workspace }

  /**
   * All classfiles and resources including upstream modules and dependencies
   * necessary to run this module's code.
   */
  def runClasspath: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * The elements of the run classpath which are local to this module.
   * This is typically the output of a compilation step and bundles runtime resources.
   */
  def localRunClasspath: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * Allows you to specify an explicit main class to use for the `run` command.
   * If none is specified, the classpath is searched for an appropriate main
   * class to use if one exists.
   */
  def mainClass: T[Option[String]] = None

  def allLocalMainClasses: T[Seq[String]] = Task {
    val classpath = localRunClasspath().map(_.path)
    if (zincWorker().javaHome().isDefined) {
      Jvm
        .callSubprocess(
          mainClass = "mill.scalalib.worker.DiscoverMainClassesMain",
          classPath = zincWorker().classpath().map(_.path),
          mainArgs = Seq(classpath.mkString(",")),
          javaHome = zincWorker().javaHome().map(_.path),
          streamOut = false
        )
        .out
        .lines()

    } else {
      zincWorker().worker().discoverMainClasses(classpath)
    }
  }

  def finalMainClassOpt: T[Either[String, String]] = Task {
    mainClass() match {
      case Some(m) => Right(m)
      case None =>
        allLocalMainClasses() match {
          case Seq() => Left("No main class specified or found")
          case Seq(main) => Right(main)
          case mains =>
            Left(
              s"Multiple main classes found (${mains.mkString(",")}) " +
                "please explicitly specify which one to use by overriding mainClass"
            )
        }
    }
  }

  def finalMainClass: T[String] = Task {
    finalMainClassOpt() match {
      case Right(main) => Result.Success(main)
      case Left(msg) => Result.Failure(msg)
    }
  }

  /**
   * Control whether `run*`-targets should use an args file to pass command line args, if possible.
   */
  def runUseArgsFile: T[Boolean] = Task { scala.util.Properties.isWin }

  /**
   * Runs this module's code in a subprocess and waits for it to finish
   */
  def run(args: Task[Args] = Task.Anon(Args())): Command[Unit] = Task.Command {
    runForkedTask(finalMainClass, args)()
  }

  /**
   * Runs this module's code in-process within an isolated classloader. This is
   * faster than `run`, but in exchange you have less isolation between runs
   * since the code can dirty the parent Mill process and potentially leave it
   * in a bad state.
   */
  def runLocal(args: Task[Args] = Task.Anon(Args())): Command[Unit] = Task.Command {
    runLocalTask(finalMainClass, args)()
  }

  /**
   * Same as `run`, but lets you specify a main class to run
   */
  def runMain(@arg(positional = true) mainClass: String, args: String*): Command[Unit] = {
    val task = runForkedTask(Task.Anon { mainClass }, Task.Anon { Args(args) })
    Task.Command { task() }
  }

  /**
   * Same as `runBackground`, but lets you specify a main class to run
   */
  def runMainBackground(@arg(positional = true) mainClass: String, args: String*): Command[Unit] = {
    val task = runBackgroundTask(Task.Anon { mainClass }, Task.Anon { Args(args) })
    Task.Command { task() }
  }

  /**
   * Same as `runLocal`, but lets you specify a main class to run
   */
  def runMainLocal(@arg(positional = true) mainClass: String, args: String*): Command[Unit] = {
    val task = runLocalTask(Task.Anon { mainClass }, Task.Anon { Args(args) })
    Task.Command { task() }
  }

  /**
   * Runs this module's code in a subprocess and waits for it to finish
   */
  def runForkedTask(mainClass: Task[String], args: Task[Args] = Task.Anon(Args())): Task[Unit] =
    Task.Anon {
      try Result.Success(
          runner().run(args = args().value, mainClass = mainClass(), workingDir = forkWorkingDir())
        )
      catch {
        case NonFatal(_) => Result.Failure("Subprocess failed")
      }
    }

  def runner: Task[RunModule.Runner] = Task.Anon {
    new RunModule.RunnerImpl(
      finalMainClassOpt(),
      runClasspath().map(_.path),
      forkArgs(),
      forkEnv(),
      runUseArgsFile(),
      zincWorker().javaHome().map(_.path)
    )
  }

  def runLocalTask(mainClass: Task[String], args: Task[Args] = Task.Anon(Args())): Task[Unit] =
    Task.Anon {
      Jvm.runLocal(
        mainClass(),
        runClasspath().map(_.path),
        args().value
      )
    }

  def runBackgroundTask(mainClass: Task[String], args: Task[Args] = Task.Anon(Args())): Task[Unit] =
    Task.Anon {
      val (procUuidPath, procLockfile, procUuid) = RunModule.backgroundSetup(T.dest)
      runner().run(
        args = Seq(
          procUuidPath.toString,
          procLockfile.toString,
          procUuid,
          runBackgroundRestartDelayMillis().toString,
          mainClass()
        ) ++ args().value,
        mainClass = "mill.scalalib.backgroundwrapper.MillBackgroundWrapper",
        workingDir = forkWorkingDir(),
        extraRunClasspath = zincWorker().backgroundWrapperClasspath().map(_.path).toSeq,
        background = true,
        runBackgroundLogToConsole = runBackgroundLogToConsole
      )
    }

  /**
   * If true, stdout and stderr of the process executed by `runBackground`
   * or `runMainBackground` is sent to mill's stdout/stderr (which usually
   * flow to the console).
   *
   * If false, output will be directed to files `stdout.log` and `stderr.log`
   * in `runBackground.dest` (or `runMainBackground.dest`)
   */
  // TODO: make this a task, to be more dynamic
  def runBackgroundLogToConsole: Boolean = true
  def runBackgroundRestartDelayMillis: T[Int] = 500

  @deprecated("Binary compat shim, use `.runner().run(..., background=true)`", "Mill 0.12.0")
  protected def doRunBackground(
      taskDest: Path,
      runClasspath: Seq[PathRef],
      zwBackgroundWrapperClasspath: Agg[PathRef],
      forkArgs: Seq[String],
      forkEnv: Map[String, String],
      finalMainClass: String,
      forkWorkingDir: Path,
      runUseArgsFile: Boolean,
      backgroundOutputs: Option[Tuple2[ProcessOutput, ProcessOutput]]
  )(args: String*): Ctx => Result[Unit] = ctx => {
    val (procUuidPath, procLockfile, procUuid) = RunModule.backgroundSetup(taskDest)
    try Result.Success(
        Jvm.runSubprocessWithBackgroundOutputs(
          "mill.scalalib.backgroundwrapper.MillBackgroundWrapper",
          (runClasspath ++ zwBackgroundWrapperClasspath).map(_.path),
          forkArgs,
          forkEnv,
          Seq(
            procUuidPath.toString,
            procLockfile.toString,
            procUuid,
            500.toString,
            finalMainClass
          ) ++ args,
          workingDir = forkWorkingDir,
          backgroundOutputs,
          useCpPassingJar = runUseArgsFile
        )(ctx)
      )
    catch {
      case e: Exception =>
        Result.Failure("subprocess failed")
    }
  }
}

object RunModule {

  private[mill] def backgroundSetup(dest: os.Path): (Path, Path, String) = {
    val procUuid = java.util.UUID.randomUUID().toString
    val procUuidPath = dest / ".mill-background-process-uuid"
    val procLockfile = dest / ".mill-background-process-lock"
    (procUuidPath, procLockfile, procUuid)
  }
  trait Runner {
    def run(
        args: os.Shellable,
        mainClass: String = null,
        forkArgs: Seq[String] = null,
        forkEnv: Map[String, String] = null,
        workingDir: os.Path = null,
        useCpPassingJar: java.lang.Boolean = null,
        extraRunClasspath: Seq[os.Path] = Nil,
        background: Boolean = false,
        runBackgroundLogToConsole: Boolean = false
    )(implicit ctx: Ctx): Unit
  }
  private class RunnerImpl(
      mainClass0: Either[String, String],
      runClasspath: Seq[os.Path],
      forkArgs0: Seq[String],
      forkEnv0: Map[String, String],
      useCpPassingJar0: Boolean,
      javaHome: Option[os.Path]
  ) extends Runner {

    def run(
        args: os.Shellable,
        mainClass: String = null,
        forkArgs: Seq[String] = null,
        forkEnv: Map[String, String] = null,
        workingDir: os.Path = null,
        useCpPassingJar: java.lang.Boolean = null,
        extraRunClasspath: Seq[os.Path] = Nil,
        background: Boolean = false,
        runBackgroundLogToConsole: Boolean = false
    )(implicit ctx: Ctx): Unit = {
      Jvm.runSubprocess(
        Option(mainClass).getOrElse(mainClass0.fold(sys.error, identity)),
        runClasspath ++ extraRunClasspath,
        Option(forkArgs).getOrElse(forkArgs0),
        Option(forkEnv).getOrElse(forkEnv0),
        args.value,
        Option(workingDir).getOrElse(ctx.dest),
        background = background,
        Option(useCpPassingJar) match {
          case Some(b) => b
          case None => useCpPassingJar0
        },
        runBackgroundLogToConsole = runBackgroundLogToConsole,
        javaHome = javaHome
      )
    }
  }
}
