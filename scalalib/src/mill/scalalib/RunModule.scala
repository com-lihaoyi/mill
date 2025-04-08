package mill.scalalib

import java.lang.reflect.Modifier

import mainargs.arg
import mill.api.JsonFormatters.pathReadWrite
import mill.api.{Ctx, PathRef, Result}
import mill.constants.ServerFiles
import mill.define.{Command, ModuleRef, Task}
import mill.util.Jvm
import mill.{Args, T}
import os.{Path, ProcessOutput}
import scala.util.control.NonFatal

import mill.scalalib.classgraph.ClassgraphWorkerModule

trait RunModule extends WithJvmWorker with mill.runner.api.RunModuleApi {

  def classgraphWorkerModule: ModuleRef[ClassgraphWorkerModule] = ModuleRef(ClassgraphWorkerModule)

  /**
   * Any command-line parameters you want to pass to the forked JVM.
   */
  def forkArgs: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Any environment variables you want to pass to the forked JVM.
   */
  def forkEnv: T[Map[String, String]] = Task { Map.empty[String, String] }

  def forkWorkingDir: T[os.Path] = Task { Task.workspace }

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

  /**
   * All main classes detected in this module that can serve as program entry-points.
   */
  def allLocalMainClasses: T[Seq[String]] = Task {
    classgraphWorkerModule().classgraphWorker().discoverMainClasses(localRunClasspath().map(_.path))
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
              s"Multiple main classes found (${mains.mkString(",")}). " +
                "Please explicitly specify which one to use by overriding `mainClass` " +
                "or using `runMain <main-class> <...args>` instead of `run`"
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
      jvmWorker().javaHome().map(_.path)
    )
  }

  def runLocalTask(mainClass: Task[String], args: Task[Args] = Task.Anon(Args())): Task[Unit] =
    Task.Anon {
      Jvm.withClassLoader(
        classPath = runClasspath().map(_.path).toVector
      ) { classloader =>
        RunModule.getMainMethod(mainClass(), classloader).invoke(null, args().value.toArray)
      }
    }

  def runBackgroundTask(mainClass: Task[String], args: Task[Args] = Task.Anon(Args())): Task[Unit] =
    Task.Anon {
      val (procUuidPath, procLockfile, procUuid) = RunModule.backgroundSetup(Task.dest)
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
        extraRunClasspath = jvmWorker().backgroundWrapperClasspath().map(_.path).toSeq,
        background = true,
        runBackgroundLogToConsole = runBackgroundLogToConsole
      )
    }

  /**
   * Runs this module's code in a background process, until it dies or
   * `runBackground` is used again. This lets you continue using Mill while
   * the process is running in the background: editing files, compiling, and
   * only re-starting the background process when you're ready.
   *
   * You can also use `-w foo.runBackground` to make Mill watch for changes
   * and automatically recompile your code & restart the background process
   * when ready. This is useful when working on long-running server processes
   * that would otherwise run forever
   */
  def runBackground(args: String*): Command[Unit] = {
    val task = runBackgroundTask(finalMainClass, Task.Anon { Args(args) })
    Task.Command { task() }
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

  private[mill] def launcher0 = Task.Anon {
    val launchClasspath =
      if (!runUseArgsFile()) runClasspath().map(_.path)
      else {
        val classpathJar = Task.dest / "classpath.jar"
        Jvm.createClasspathPassingJar(classpathJar, runClasspath().map(_.path))
        Seq(classpathJar)
      }

    Jvm.createLauncher(finalMainClass(), launchClasspath, forkArgs())
  }

  /**
   * Builds a command-line "launcher" file that can be used to run this module's
   * code, without the Mill process. Useful for deployment & other places where
   * you do not want a build tool running
   */
  def launcher: T[PathRef] = Task { launcher0() }

  def bspJvmRunTestEnvironment = {
    val moduleSpecificTask = this match {
      case m: (TestModule & JavaModule) => m.getTestEnvironmentVars()
      case _ => allLocalMainClasses
    }
    Task.Anon {
      (
        runClasspath().map(_.path.toNIO),
        forkArgs(),
        forkWorkingDir().toNIO,
        forkEnv(),
        mainClass(),
        moduleSpecificTask()
      )
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

  private[mill] def getMainMethod(mainClassName: String, cl: ClassLoader) = {
    val mainClass = cl.loadClass(mainClassName)
    val method = mainClass.getMethod("main", classOf[Array[String]])
    // jvm allows the actual main class to be non-public and to run a method in the non-public class,
    //  we need to make it accessible
    method.setAccessible(true)
    val modifiers = method.getModifiers
    if (!Modifier.isPublic(modifiers))
      throw new NoSuchMethodException(mainClassName + ".main is not public")
    if (!Modifier.isStatic(modifiers))
      throw new NoSuchMethodException(mainClassName + ".main is not static")
    method
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
      val dest = ctx.dest
      val cwd = Option(workingDir).getOrElse(dest)
      val mainClass1 = Option(mainClass).getOrElse(mainClass0.fold(sys.error, identity))
      val mainArgs = args.value
      val classPath = runClasspath ++ extraRunClasspath
      val jvmArgs = Option(forkArgs).getOrElse(forkArgs0)
      Option(useCpPassingJar) match {
        case Some(b) => b: Boolean
        case None => useCpPassingJar0
      }
      val env = Option(forkEnv).getOrElse(forkEnv0)

      os.checker.withValue(os.Checker.Nop) {
        if (background) {
          val (stdout, stderr) = if (runBackgroundLogToConsole) {
            // Hack to forward the background subprocess output to the Mill server process
            // stdout/stderr files, so the output will get properly slurped up by the Mill server
            // and shown to any connected Mill client even if the current command has completed
            val pwd0 = os.Path(java.nio.file.Paths.get(".").toAbsolutePath)
            (
              os.PathAppendRedirect(pwd0 / ".." / ServerFiles.stdout),
              os.PathAppendRedirect(pwd0 / ".." / ServerFiles.stderr)
            )
          } else {
            (dest / "stdout.log": os.ProcessOutput, dest / "stderr.log": os.ProcessOutput)
          }
          Jvm.spawnProcess(
            mainClass = mainClass1,
            classPath = classPath,
            jvmArgs = jvmArgs,
            env = env,
            mainArgs = mainArgs,
            cwd = cwd,
            stdin = "",
            stdout = stdout,
            stderr = stderr,
            cpPassingJarPath =
              Some(os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)),
            javaHome = javaHome,
            destroyOnExit = false
          )
        } else {
          Jvm.callProcess(
            mainClass = mainClass1,
            classPath = classPath,
            jvmArgs = jvmArgs,
            env = env,
            mainArgs = mainArgs,
            cwd = cwd,
            stdin = os.Inherit,
            stdout = os.Inherit,
            stderr = os.Inherit,
            cpPassingJarPath =
              Some(os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)),
            javaHome = javaHome
          )
        }
      }
    }
  }

}
