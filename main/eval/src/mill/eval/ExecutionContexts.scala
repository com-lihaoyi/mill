package mill.eval

import mill.api.BlockableExecutionContext

import scala.concurrent.{Future, Await}
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
    def await[T](t: => Future[T]): T = Await.result(t, Duration.Inf)
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = {}
    def close(): Unit = () // do nothing
  }

  /**
   * A simple thread-pool-based ExecutionContext with configurable thread count
   * and AutoCloseable support
   */
  class ThreadPool(threadCount: Int) extends BlockableExecutionContext {
    def await[T](t: => Future[T]): T = blocking { Await.result(t, Duration.Inf) }
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

    def execute(runnable: Runnable): Unit = threadPool.submit(runnable)
    def reportFailure(t: Throwable): Unit = {}
    def close(): Unit = threadPool.shutdown()
  }
}
