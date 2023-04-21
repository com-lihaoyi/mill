package mill.api

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.language.implicitConversions
import os.Path

/**
 * Provides access to various resources in the context of a currently execution Target.
 */
object Ctx {
  @compileTimeOnly("Target.ctx() / T.ctx() / T.* APIs can only be used with a T{...} block")
  @ImplicitStub
  implicit def taskCtx: Ctx = ???

  /**
   * Access to the targets [[dest]] path.
   */
  trait Dest {

    /**
     * `T.dest` is a unique `os.Path` (e.g. `out/classFiles.dest/` or `out/run.dest/`)
     * that is assigned to every Target or Command. It is cleared before your
     * task runs, and you can use it as a scratch space for temporary files or
     * a place to put returned artifacts. This is guaranteed to be unique for
     * every Target or Command, so you can be sure that you will not collide or
     * interfere with anyone else writing to those same paths.
     */
    def dest: os.Path
  }
  object Dest {
    implicit def pathToCtx(path: os.Path): Dest = new Dest { def dest = path }
  }

  /** Access to the targets [[Logger]] instance. */
  trait Log {

    /**
     * `T.log` is the default logger provided for every task. While your task is running,
     * `System.out` and `System.in` are also redirected to this logger. The logs for a
     * task are streamed to standard out/error as you would expect, but each task's
     * specific output is also streamed to a log file on disk, e.g. `out/run.log` or
     * `out/classFiles.log` for you to inspect later.
     *
     * Messages logged with `log.debug` appear by default only in the log files.
     * You can use the `--debug` option when running mill to show them on the console too.
     */
    def log: Logger
  }
  object Log {

    implicit def logToCtx(l: Logger): Log = new Log { def log = l }
  }

  /**
   * Access to some internal storage dir used by underlying ammonite.
   * You should not need this in a buildscript.
   */
  trait Home {
    def home: os.Path
  }

  /** Access to the current system environment settings. */
  trait Env {

    /**
     * `T.env` is the environment variable map passed to the Mill command when
     * it is run; typically used inside a `T.input` to ensure any changes in
     * the env vars are properly detected.
     *
     * Note that you should not use `sys.env`, as Mill's long-lived server
     * process means that `sys.env` variables may not be up to date.
     */
    def env: Map[String, String]
  }

  trait Args {
    def args: IndexedSeq[_]
  }

  /**
   * Access to the selected parallel job count (`mill --jobs`).
   */
  trait Jobs {
    def jobs: Int
  }

  /** Access to the project root (aka workspace) directory. */
  trait Workspace {

    /**
     * This is the `os.Path` pointing to the project root directory.
     *
     * This is the preferred access to the project directory, and should
     * always be prefered over `os.pwd`* (which might also point to the
     * project directory in classic cli scenarios, but might not in other
     * use cases like BSP or LSP server usage).
     */
    def workspace: os.Path
  }

  def defaultHome = os.home / ".mill" / "ammonite"

  /**
   * Marker annotation.
   */
  class ImplicitStub extends StaticAnnotation
}

/**
 * Represents the data and utilities that are contextually available inside the
 * implementation of a `Task`.
 */
class Ctx(
    val args: IndexedSeq[_],
    dest0: () => os.Path,
    val log: Logger,
    val home: os.Path,
    val env: Map[String, String],
    val reporter: Int => Option[CompileProblemReporter],
    val testReporter: TestReporter,
    val workspace: os.Path
) extends Ctx.Dest
    with Ctx.Log
    with Ctx.Args
    with Ctx.Home
    with Ctx.Env
    with Ctx.Workspace {

  def dest: Path = dest0()
  def arg[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
