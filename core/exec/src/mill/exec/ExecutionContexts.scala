package mill.exec

import mill.internal.{FileLogger, MultiLogger, PrefixLogger}
import os.Path

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{PriorityBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import mill.api.Logger
import mill.api.daemon.internal.{NonFatal, PriorityRunnable}

object ExecutionContexts {

  /**
   * Execution context that runs code immediately when scheduled, without
   * spawning a separate thread or thread-pool. Used to turn parallel-async
   * Future code into nice single-threaded code without needing to rewrite it
   */
  object RunNow extends mill.api.TaskCtx.Fork.Impl {
    def await[T](t: Future[T]): T = Await.result(t, Duration.Inf)
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = {}
    def close(): Unit = () // do nothing

    def blocking[T](t: => T): T = t
    // Under `--jobs=1`, `Fork.async` routes here and runs the body inline on the
    // parent thread, sharing the parent's `os.pwd` and system streams. Unlike
    // `ThreadPool.async`, this does NOT relocate `os.pwd` into the `dest` sandbox
    // folder, does NOT materialize `dest`, and does NOT write a per-future
    // `dest.log` or add a terminal prompt line. The pwd-sandbox-in-`dest` +
    // per-future `dest.log` contract documented on [[mill.api.TaskCtx.Fork.async]]
    // is therefore not honored here; only the value of the body is surfaced
    // through the returned `Future`.
    def async[T](dest: Path, key: String, message: String, priority: Int)(t: Logger => T)(using
        ctx: mill.api.TaskCtx
    ): Future[T] =
      // A throwing body yields a failed Future, matching `ThreadPool.async`, so
      // `fork.async` failure semantics don't differ between `--jobs=1` and `--jobs>1`.
      Future.fromTry(NonFatal.Try(t(ctx.log)))
  }

  /**
   * A simple thread-pool-based ExecutionContext with configurable thread count
   * and AutoCloseable support
   */
  class ThreadPool(executor: ThreadPoolExecutor) extends mill.api.TaskCtx.Fork.Impl {
    private class AsyncRunnable(priority: Int, run0: () => Unit)
        extends PriorityRunnable(priority, run0) {
      override private[mill] def samePriorityGroup: Int = -1
    }

    private def runNextQueuedAsync(): Boolean =
      executor.getQueue.poll() match {
        case runnable: AsyncRunnable =>
          runnable.run()
          true
        case null => false
        case runnable =>
          // A higher-priority ordinary task may have arrived before the poll.
          // Its stable PriorityRunnable index preserves its place when reinserted.
          executor.getQueue.add(runnable)
          false
      }

    def await[T](t: Future[T]): T = {
      while (!t.isCompleted && runNextQueuedAsync()) ()
      if (t.isCompleted) Await.result(t, Duration.Inf)
      else blocking { Await.result(t, Duration.Inf) }
    }

    // Synchronize on the underlying `executor`: concurrent launchers
    // share one daemon-level executor across multiple `ThreadPool`
    // wrappers, so locking on `this` wouldn't serialise the
    // read-modify-write of core/max pool sizes.
    def enterBlocking(): Unit = executor.synchronized {
      val newCorePoolSize = executor.getCorePoolSize + 1
      if (newCorePoolSize > executor.getMaximumPoolSize) {
        executor.setMaximumPoolSize(newCorePoolSize)
      }
      executor.setCorePoolSize(newCorePoolSize)
    }

    // Scale both core AND max back down so the extra worker spun up for the
    // `blocking{...}` span is reaped once idle, restoring the `--jobs`
    // parallelism bound instead of leaving leftover threads draining the
    // queue. Lower core first so the `core <= max` invariant always holds.
    // The resulting thread churn no longer fragments the chrome profile,
    // because [[mill.internal.ThreadNumberer]] now recycles profile lanes
    // independently of physical thread identity.
    def leaveBlocking(): Unit = executor.synchronized {
      executor.setCorePoolSize(executor.getCorePoolSize - 1)
      executor.setMaximumPoolSize(executor.getMaximumPoolSize - 1)
    }

    def blocking[T](t: => T): T = {
      enterBlocking()
      try t
      finally leaveBlocking()
    }

    def execute(runnable: Runnable): Unit = {
      val submitterContext = TaskThreadContext.capture()
      executor.execute(new PriorityRunnable(
        0,
        () => submitterContext.bind(runnable.run())
      ))

    }

    def reportFailure(t: Throwable): Unit = {}
    def close(): Unit = executor.shutdown()

    /**
     * A variant of `scala.concurrent.Future{...}` that sets the `pwd` to a different
     * folder [[dest]] and duplicates the logging streams to [[dest]].log while evaluating
     * [[t]], to avoid conflict with other tasks that may be running concurrently
     */
    private[exec] def async0[T](priority: Int)(t: => T): Future[T] = {
      val promise = concurrent.Promise[T]()
      executor.execute(new AsyncRunnable(
        priority = priority,
        run0 = () => promise.complete(NonFatal.Try(t))
      ))
      promise.future
    }

    def async[T](dest: Path, key: String, message: String, priority: Int)(t: Logger => T)(using
        ctx: mill.api.TaskCtx
    ): Future[T] = {
      val logger = MultiLogger(
        PrefixLogger(ctx.log, Seq(key), ctx.log.keySuffix, message),
        FileLogger(dest / os.up / s"${dest.last}.log", false),
        ctx.log.streams.in
      )

      val lazyDest = new LazyDest(() => dest)
      val submitterContext = TaskThreadContext.capture(
        pwd = () => lazyDest.get(),
        streams = logger.streams
      )
      async0(priority) {
        logger.withPromptLine {
          submitterContext.bind(t(logger))
        }
      }
    }
  }

  private val executorCounter = new AtomicInteger
  def createExecutor(threadCount: Int): ThreadPoolExecutor = {
    val executorIndex = executorCounter.incrementAndGet()
    val threadCounter = new AtomicInteger
    ThreadPoolExecutor(
      threadCount,
      threadCount,
      60,
      TimeUnit.SECONDS,
      // Use a priority queue so child `fork.async` tasks can run ahead of
      // same- or lower-priority parent tasks that may await them.
      PriorityBlockingQueue[Runnable](),
      runnable => {
        val threadIndex = threadCounter.incrementAndGet()
        val t = Thread(
          runnable,
          s"execution-contexts-threadpool-$executorIndex-thread-$threadIndex"
        )
        t.setDaemon(true)
        t
      }
    )
  }
}
