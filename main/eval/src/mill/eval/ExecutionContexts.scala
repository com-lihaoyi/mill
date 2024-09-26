package mill.eval

import mill.api.BlockableExecutionContext
import os.Path

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import java.util.concurrent.ForkJoinPool.ManagedBlocker
import java.util.concurrent.{ExecutorService, ForkJoinPool}

private object ExecutionContexts {

  /**
   * Execution context that runs code immediately when scheduled, without
   * spawning a separate thread or thread-pool. Used to turn parallel-async
   * Future code into nice single-threaded code without needing to rewrite it
   */
  object RunNow extends BlockableExecutionContext {
    def await[T](t: Future[T]): T = Await.result(t, Duration.Inf)
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = {}
    def close(): Unit = () // do nothing

    def sandboxedFuture[T](dest: Path, key: String, message: String)(t: => T)(implicit
        ctx: mill.api.Ctx
    ): Future[T] =
      Future.successful(t)
  }

  /**
   * A simple thread-pool-based ExecutionContext with configurable thread count
   * and AutoCloseable support
   */
  class ThreadPool(threadCount: Int) extends BlockableExecutionContext {
    def await[T](t: Future[T]): T = blocking { Await.result(t, Duration.Inf) }
    val forkJoinPool: ForkJoinPool = new ForkJoinPool(threadCount)
    val threadPool: ExecutorService = forkJoinPool

    def blocking[T](t: => T): T = {
      @volatile var res: Option[T] = None
      ForkJoinPool.managedBlock(new ManagedBlocker {
        def block(): Boolean = {
          if (res.isEmpty) res = Some(t)
          true
        }
        def isReleasable: Boolean = res.nonEmpty
      })
      res.get
    }

    def execute(runnable: Runnable): Unit = {
      // By default any child task inherits the pwd and system streams from the
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
    def sandboxedFuture[T](dest: Path, key: String, message: String)(t: => T)(implicit
        ctx: mill.api.Ctx
    ): Future[T] = {
      val logger = ctx.log.subLogger(dest / os.up / s"${dest.last}.log", key, message)

      var destInitialized: Boolean = false
      def makeDest() = synchronized {
        if (!destInitialized) {
          os.makeDir.all(dest)
          destInitialized = true
        }

        dest
      }
      Future {
        logger.withPrompt {
          os.dynamicPwdFunction.withValue(() => makeDest()) {
            mill.api.SystemStreams.withStreams(logger.systemStreams) {
              t
            }
          }
        }
      }(this)
    }
  }
}
