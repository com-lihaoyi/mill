package mill.eval

import scala.concurrent.ExecutionContext

private object ExecutionContexts {

  /**
   * Execution context that runs code immediately when scheduled, without
   * spawning a separate thread or thread-pool. Used to turn parallel-async
   * Future code into nice single-threaded code without needing to rewrite it
   */
  object RunNow extends ExecutionContext with AutoCloseable {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = {}
    def close(): Unit = () // do nothing
  }

  /**
   * A simple thread-pool-based ExecutionContext with configurable thread count
   * and AutoCloseable support
   */
  class ThreadPool(threadCount: Int) extends ExecutionContext with AutoCloseable {
    val threadPool = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
    def execute(runnable: Runnable): Unit = threadPool.submit(runnable)
    def reportFailure(t: Throwable): Unit = {}
    def close(): Unit = threadPool.shutdown()
  }
}
