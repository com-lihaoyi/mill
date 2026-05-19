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
    def async[T](dest: Path, key: String, message: String, priority: Int)(t: Logger => T)(using
        ctx: mill.api.TaskCtx
    ): Future[T] =
      Future.successful(t(ctx.log))
  }

  /**
   * A simple thread-pool-based ExecutionContext with configurable thread count
   * and AutoCloseable support
   */
  class ThreadPool(executor: ThreadPoolExecutor) extends mill.api.TaskCtx.Fork.Impl {
    def await[T](t: Future[T]): T = blocking { Await.result(t, Duration.Inf) }

    // Synchronize on the underlying `executor`: concurrent launchers
    // share one daemon-level executor across multiple `ThreadPool`
    // wrappers, so locking on `this` wouldn't serialise the
    // read-modify-write of core/max pool sizes.
    def updateThreadCount(delta: Int): Unit = executor.synchronized {
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
      val submitterPwd = os.dynamicPwdFunction.value
      val submitterChecker = os.checker.value
      val submitterStreams = new mill.api.SystemStreams(Console.out, Console.err, System.in)
      val submitterModuleWatched = mill.api.BuildCtx.watchedValues0.value
      val submitterEvalWatched = mill.api.BuildCtx.evalWatchedValues0.value
      executor.execute(new PriorityRunnable(
        0,
        () =>
          os.checker.withValue(submitterChecker) {
            os.dynamicPwdFunction.withValue(() => submitterPwd()) {
              mill.api.SystemStreamsUtils.withStreams(submitterStreams) {
                mill.api.BuildCtx.watchedValues0.withValue(submitterModuleWatched) {
                  mill.api.BuildCtx.evalWatchedValues0.withValue(submitterEvalWatched) {
                    runnable.run()
                  }
                }
              }
            }
          }
      ))

    }

    def reportFailure(t: Throwable): Unit = {}
    def close(): Unit = executor.shutdown()

    /**
     * A variant of `scala.concurrent.Future{...}` that sets the `pwd` to a different
     * folder [[dest]] and duplicates the logging streams to [[dest]].log while evaluating
     * [[t]], to avoid conflict with other tasks that may be running concurrently
     */
    def async[T](dest: Path, key: String, message: String, priority: Int)(t: Logger => T)(using
        ctx: mill.api.TaskCtx
    ): Future[T] = {
      val logger = MultiLogger(
        PrefixLogger(ctx.log, Seq(key), ctx.log.keySuffix, message),
        FileLogger(dest / os.up / s"${dest.last}.log", false),
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
      val submitterChecker = os.checker.value
      val submitterModuleWatched = mill.api.BuildCtx.watchedValues0.value
      val submitterEvalWatched = mill.api.BuildCtx.evalWatchedValues0.value
      val promise = concurrent.Promise[T]
      val runnable = new PriorityRunnable(
        priority = priority,
        run0 = () => {
          val result = NonFatal.Try(logger.withPromptLine {
            os.checker.withValue(submitterChecker) {
              os.dynamicPwdFunction.withValue(() => makeDest()) {
                mill.api.SystemStreamsUtils.withStreams(logger.streams) {
                  mill.api.BuildCtx.watchedValues0.withValue(submitterModuleWatched) {
                    mill.api.BuildCtx.evalWatchedValues0.withValue(submitterEvalWatched) {
                      t(logger)
                    }
                  }
                }
              }
            }
          })
          promise.complete(result)
        }
      )

      executor.execute(runnable)
      promise.future
    }
  }

  private val executorCounter = new AtomicInteger
  def createExecutor(threadCount: Int): ThreadPoolExecutor = {
    val executorIndex = executorCounter.incrementAndGet()
    val threadCounter = new AtomicInteger
    ThreadPoolExecutor(
      threadCount,
      threadCount,
      60 * 1000,
      TimeUnit.SECONDS,
      // Use a priority queue so child `fork.async` tasks can run ahead of
      // lower-priority parent tasks, avoiding large numbers of blocked parent
      // tasks from piling up.
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
