package mill.api

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.language.implicitConversions

/**
 * Represents the data and utilities that are contextually available inside the
 * implementation of a `Task`.
 */
trait Ctx extends Ctx.Dest
    with Ctx.Log
    with Ctx.Args
    with Ctx.Env
    with Ctx.Workspace
    with Ctx.Fork
    with Ctx.Jobs {
  def reporter: Int => Option[CompileProblemReporter]

  def testReporter: TestReporter
  def systemExit: Int => Nothing
}

/**
 * Provides access to various resources in the context of a current execution Target.
 */
object Ctx {

  private[mill] class Impl(
      val args: IndexedSeq[?],
      dest0: () => os.Path,
      val log: Logger,
      val env: Map[String, String],
      val reporter: Int => Option[CompileProblemReporter],
      val testReporter: TestReporter,
      val workspace: os.Path,
      val systemExit: Int => Nothing,
      val fork: Ctx.Fork.Api,
      val jobs: Int
  ) extends Ctx {
    def dest: os.Path = dest0()

    def arg[T](index: Int): T = {
      if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
      else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
    }
  }

  @compileTimeOnly(
    "Target.ctx() / Task.ctx() / Task.* APIs can only be used with a Task{...} block"
  )
  @ImplicitStub
  implicit def taskCtx: Ctx = ???

  /**
   * Access to the targets [[dest]] path.
   */
  trait Dest {

    /**
     * `Task.dest` is a unique `os.Path` (e.g. `out/classFiles.dest/` or `out/run.dest/`)
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
     * `Task.log` is the default logger provided for every task. While your task is running,
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

  /** Access to the current system environment settings. */
  trait Env {

    /**
     * `Task.env` is the environment variable map passed to the Mill command when
     * it is run; typically used inside a `Task.Input` to ensure any changes in
     * the env vars are properly detected.
     *
     * Note that you should not use `sys.env`, as Mill's long-lived server
     * process means that `sys.env` variables may not be up to date.
     */
    def env: Map[String, String]
  }

  trait Args {
    def args: IndexedSeq[?]
    def arg[T](index: Int): T
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
     * always be preferred over `os.pwd`* (which might also point to the
     * project directory in classic cli scenarios, but might not in other
     * use cases like BSP or LSP server usage).
     */
    def workspace: os.Path
  }

  /**
   * Marker annotation.
   */
  class ImplicitStub extends StaticAnnotation

  trait Fork {

    /**
     * Provides APIs for Mill tasks to spawn `async` "future" computations that
     * can be `await`ed upon to yield their result. Unlike other thread pools or
     * `Executor`s, `fork.async` spawns futures that follow Mill's `-j`/`--jobs` config,
     * sandbox their `os.pwd` in separate folders, and integrate with Mill's terminal
     * logging prefixes and prompt so a user can easily see what futures are running
     * and what logs belong to each future.
     */
    def fork: Fork.Api
  }

  @experimental
  object Fork {
    import scala.concurrent.{ExecutionContext, Future}
    trait Api {

      def blocking[T](t: => T): T

      /**
       * Awaits for the result for the given async future and returns the resultant value
       */
      def await[T](t: Future[T]): T

      /**
       * Awaits for the result for multiple async futures and returns the resultant values
       */
      def awaitAll[T](t: Seq[Future[T]]): Seq[T]

      /**
       * Spawns an async workflow. Mill async futures require additional metadata
       * to sandbox, store logs, and integrate them into Mills terminal prompt logger
       *
       * @param dest The "sandbox" folder that will contain the `os.pwd` and the `pwd` for
       *             any subprocesses spawned within the async future. Also provides the
       *             path for the log file (dest + ".log") for any stdout/stderr `println`s
       *             that occur within that future
       * @param key The short prefix, typically a number ("1", "2", "3", etc.) that will be
       *            used to prefix all log lines emitted within this async future in the
       *            terminal to allow them to be distinguished from other logs
       * @param message A one-line summary of what this async future is doing, used in the
       *                terminal prompt to display what this future is currently computing.
       * @param priority 0 means the same priority as other Mill tasks, negative values <0
       *                 mean increasingly high priority, positive values >0 mean increasingly
       *                 low priority
       * @param t The body of the async future
       */
      def async[T](
          dest: os.Path,
          key: String,
          message: String,
          priority: Int = 0
      )(t: Logger => T)(implicit
          ctx: mill.api.Ctx
      ): Future[T]
    }

    trait Impl extends Api with ExecutionContext with AutoCloseable {
      def awaitAll[T](t: Seq[Future[T]]): Seq[T] = {
        implicit val ec = this
        await(Future.sequence(t))
      }
    }
  }
}
