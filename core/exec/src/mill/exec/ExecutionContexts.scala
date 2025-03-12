package mill.exec

import mill.internal.{FileLogger, MultiLogger, PrefixLogger}
import os.Path

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import java.util.concurrent.{ExecutorService, LinkedBlockingDeque, ThreadPoolExecutor, TimeUnit}
import mill.api.Logger

private object ExecutionContexts {

  /**
   * Execution context that runs code immediately when scheduled, without
   * spawning a separate thread or thread-pool. Used to turn parallel-async
   * Future code into nice single-threaded code without needing to rewrite it
   */
  object RunNow extends mill.api.Ctx.Fork.Impl {
    def await[T](t: Future[T]): T = Await.result(t, Duration.Inf)
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = {}
    def close(): Unit = () // do nothing

    def async[T](dest: Path, key: String, message: String)(t: Logger => T)(implicit
        ctx: mill.api.Ctx
    ): Future[T] =
      Future.successful(t(ctx.log))
  }

  /**
   * A simple thread-pool-based ExecutionContext with configurable thread count
   * and AutoCloseable support
   */
  class ThreadPool(threadCount0: Int) extends mill.api.Ctx.Fork.Impl {
    def await[T](t: Future[T]): T = blocking { Await.result(t, Duration.Inf) }
    val executor: ThreadPoolExecutor = new ThreadPoolExecutor(
      threadCount0,
      threadCount0,
      0,
      TimeUnit.SECONDS,
      // Use a `Deque` rather than a normal `Queue`, with the various `poll`/`take`
      // operations reversed, providing elements in a LIFO order. This ensures that
      // child `fork.async` tasks always take priority over parent tasks, avoiding
      // large numbers of blocked parent tasks from piling up
      new LinkedBlockingDeque[Runnable]() {
        override def poll(): Runnable = super.pollLast()
        override def poll(timeout: Long, unit: TimeUnit): Runnable = super.pollLast(timeout, unit)
        override def take(): Runnable = super.takeLast()
      }
    )

    val threadPool: ExecutorService = executor

    def updateThreadCount(delta: Int): Unit = synchronized {
      if (delta > 0) {
        executor.setMaximumPoolSize(executor.getMaximumPoolSize + delta)
        executor.setCorePoolSize(executor.getCorePoolSize + delta)
      } else {
        executor.setCorePoolSize(executor.getCorePoolSize + delta)
        executor.setMaximumPoolSize(executor.getMaximumPoolSize + delta)
      }
    }

    def blocking[T](t: => T): T = {
      updateThreadCount(1)
      try t
      finally updateThreadCount(-1)
    }

    def execute(runnable: Runnable): Unit = {
      // By default, any child task inherits the pwd and system streams from the
      // context which submitted it
      lazy val submitterPwd = os.pwd
      lazy val submitterStreams = new mill.api.SystemStreams(System.out, System.err, System.in)
      threadPool.submit(new Runnable {
        def run() = {
          os.dynamicPwdFunction.withValue(() => submitterPwd) {
            mill.api.SystemStreams.withStreams(submitterStreams) {
              runnable.run()
            }
          }
        }
      })
    }

    def reportFailure(t: Throwable): Unit = {}
    def close(): Unit = threadPool.shutdown()

    /**
     * A variant of `scala.concurrent.Future{...}` that sets the `pwd` to a different
     * folder [[dest]] and duplicates the logging streams to [[dest]].log while evaluating
     * [[t]], to avoid conflict with other tasks that may be running concurrently
     */
    def async[T](dest: Path, key: String, message: String)(t: Logger => T)(implicit
        ctx: mill.api.Ctx
    ): Future[T] = {
      val logger = new MultiLogger(
        new PrefixLogger(ctx.log, Seq(key), ctx.log.keySuffix, message),
        new FileLogger(dest / os.up / s"${dest.last}.log", false),
        ctx.log.streams.in
      )

      var destInitialized: Boolean = false
      def makeDest() = synchronized {
        if (!destInitialized) {
          os.makeDir.all(dest)
          destInitialized = true
        }

        dest
      }
      Future {
        logger.withPromptLine {
          os.dynamicPwdFunction.withValue(() => makeDest()) {
            mill.api.SystemStreams.withStreams(logger.streams) {
              t(logger)
            }
          }
        }
      }(this)
    }
  }
}
