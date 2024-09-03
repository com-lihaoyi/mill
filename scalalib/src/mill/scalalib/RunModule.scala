package mill.scalalib

import mainargs.arg
import mill.api.JsonFormatters.pathReadWrite
import mill.api.{Ctx, PathRef, Result}
import mill.define.{Command, Task}
import mill.main.client.EnvVars
import mill.util.Jvm
import mill.{Agg, Args, T}
import os.{Path, ProcessOutput}

import scala.util.control.NonFatal

trait RunModule extends WithZincWorker {

  /**
   * Any command-line parameters you want to pass to the forked JVM.
   */
  def forkArgs: T[Seq[String]] = T { Seq.empty[String] }

  /**
   * Any environment variables you want to pass to the forked JVM.
   */
  def forkEnv: T[Map[String, String]] = T { Map.empty[String, String] }

  def forkWorkingDir: T[os.Path] = T { T.workspace }

  /**
   * All classfiles and resources including upstream modules and dependencies
   * necessary to run this module's code.
   */
  def runClasspath: T[Seq[PathRef]] = T { Seq.empty[PathRef] }

  /**
   * The elements of the run classpath which are local to this module.
   * This is typically the output of a compilation step and bundles runtime resources.
   */
  def localRunClasspath: T[Seq[PathRef]] = T { Seq.empty[PathRef] }

  /**
   * Allows you to specify an explicit main class to use for the `run` command.
   * If none is specified, the classpath is searched for an appropriate main
   * class to use if one exists.
   */
  def mainClass: T[Option[String]] = None

  def allLocalMainClasses: T[Seq[String]] = T {
    zincWorker().worker().discoverMainClasses(localRunClasspath().map(_.path))
  }

  def finalMainClassOpt: T[Either[String, String]] = T {
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

  def finalMainClass: T[String] = T {
    finalMainClassOpt() match {
      case Right(main) => Result.Success(main)
      case Left(msg) => Result.Failure(msg)
    }
  }

  /**
   * Control whether `run*`-targets should use an args file to pass command line args, if possible.
   */
  def runUseArgsFile: T[Boolean] = T { scala.util.Properties.isWin }

  /**
   * Runs this module's code in a subprocess and waits for it to finish
   */
  def run(args: Task[Args] = T.task(Args())): Command[Unit] = T.command {
    runForkedTask(finalMainClass, args)
  }

  /**
   * Runs this module's code in-process within an isolated classloader. This is
   * faster than `run`, but in exchange you have less isolation between runs
   * since the code can dirty the parent Mill process and potentially leave it
   * in a bad state.
   */
  def runLocal(args: Task[Args] = T.task(Args())): Command[Unit] = T.command {
    runLocalTask(finalMainClass, args)
  }

  /**
   * Same as `run`, but lets you specify a main class to run
   */
  def runMain(@arg(positional = true) mainClass: String, args: String*): Command[Unit] = {
    val task = runForkedTask(T.task { mainClass }, T.task { Args(args) })
    T.command { task }
  }

  /**
   * Same as `runBackground`, but lets you specify a main class to run
   */
  def runMainBackground(@arg(positional = true) mainClass: String, args: String*): Command[Unit] = {
    val task = runBackgroundTask(T.task { mainClass }, T.task { Args(args) })
    T.command { task }
  }

  /**
   * Same as `runLocal`, but lets you specify a main class to run
   */
  def runMainLocal(@arg(positional = true) mainClass: String, args: String*): Command[Unit] = {
    val task = runLocalTask(T.task { mainClass }, T.task { Args(args) })
    T.command { task }
  }

  /**
   * Runs this module's code in a subprocess and waits for it to finish
   */
  def runForkedTask(mainClass: Task[String], args: Task[Args] = T.task(Args())): Task[Unit] =
    T.task {
      try Result.Success(
          Jvm.runSubprocess(
            mainClass(),
            runClasspath().map(_.path),
            forkArgs(),
            forkEnv(),
            args().value,
            workingDir = forkWorkingDir(),
            useCpPassingJar = runUseArgsFile()
          )
        )
      catch {
        case NonFatal(_) => Result.Failure("Subprocess failed")
      }
    }

  def runLocalTask(mainClass: Task[String], args: Task[Args] = T.task(Args())): Task[Unit] =
    T.task {
      Jvm.runLocal(
        mainClass(),
        runClasspath().map(_.path),
        args().value
      )
    }

  def runBackgroundTask(mainClass: Task[String], args: Task[Args] = T.task(Args())): Task[Unit] =
    T.task {
      doRunBackground(
        taskDest = T.dest,
        runClasspath = runClasspath(),
        zwBackgroundWrapperClasspath = zincWorker().backgroundWrapperClasspath(),
        forkArgs = forkArgs(),
        forkEnv = forkEnv(),
        finalMainClass = mainClass(),
        forkWorkingDir = forkWorkingDir(),
        runUseArgsFile = runUseArgsFile(),
        backgroundOutputs = backgroundOutputs(T.dest)
      )(args().value: _*)(T.ctx())

      // Make sure to sleep a bit in the Mill test suite to allow the servers we
      // start time to initialize before we proceed with the following commands
      if (T.env.contains(EnvVars.MILL_TEST_SUITE)) {
        println("runBackgroundTask SLEEPING 10000")
        Thread.sleep(5000)
      }
    }

  /**
   * If true, stdout and stderr of the process executed by `runBackground`
   * or `runMainBackground` is sent to mill's stdout/stderr (which usualy
   * flow to the console).
   *
   * If false, output will be directed to files `stdout.log` and `stderr.log`
   * in `runBackground.dest` (or `runMainBackground.dest`)
   */
  // TODO: make this a task, to be more dynamic
  def runBackgroundLogToConsole: Boolean = true

  private def backgroundOutputs(dest: os.Path): Option[(ProcessOutput, ProcessOutput)] = {
    if (runBackgroundLogToConsole) Some((os.Inherit, os.Inherit))
    else Jvm.defaultBackgroundOutputs(dest)
  }

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
    val (procId, procTombstone, token) = backgroundSetup(taskDest)
    try Result.Success(
        Jvm.runSubprocessWithBackgroundOutputs(
          "mill.scalalib.backgroundwrapper.BackgroundWrapper",
          (runClasspath ++ zwBackgroundWrapperClasspath).map(_.path),
          forkArgs,
          forkEnv,
          Seq(procId.toString, procTombstone.toString, token, finalMainClass) ++ args,
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

  private[this] def backgroundSetup(dest: os.Path): (Path, Path, String) = {
    val token = java.util.UUID.randomUUID().toString
    val procId = dest / ".mill-background-process-id"
    val procTombstone = dest / ".mill-background-process-tombstone"
    // The background subprocesses poll the procId file, and kill themselves
    // when the procId file is deleted. This deletion happens immediately before
    // the body of these commands run, but we cannot be sure the subprocess has
    // had time to notice.
    //
    // To make sure we wait for the previous subprocess to
    // die, we make the subprocess write a tombstone file out when it kills
    // itself due to procId being deleted, and we wait a short time on task-start
    // to see if such a tombstone appears. If a tombstone appears, we can be sure
    // the subprocess has killed itself, and can continue. If a tombstone doesn't
    // appear in a short amount of time, we assume the subprocess exited or was
    // killed via some other means, and continue anyway.
    val start = System.currentTimeMillis()
    while ({
      if (os.exists(procTombstone)) {
        Thread.sleep(10)
        os.remove.all(procTombstone)
        true
      } else {
        Thread.sleep(10)
        System.currentTimeMillis() - start < 100
      }
    }) ()

    os.write(procId, token)
    os.write(procTombstone, token)
    (procId, procTombstone, token)
  }

}
