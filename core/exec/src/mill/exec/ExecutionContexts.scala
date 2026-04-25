package mill.exec

import mill.internal.{FileLogger, MultiLogger, PrefixLogger}
import os.Path

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{PriorityBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import mill.api.Logger
import mill.api.daemon.internal.NonFatal

object ExecutionContexts {
  private object RunnablePriority {
    private val priorityMethod = new ClassValue[Option[java.lang.reflect.Method]] {
      override def computeValue(clazz: Class[?]): Option[java.lang.reflect.Method] =
        try {
          val method = clazz.getMethod("priority")
          method.trySetAccessible()
          Some(method)
        } catch {
          case _: ReflectiveOperationException => None
        }
    }

    def apply(runnable: Runnable): Int =
      priorityMethod.get(runnable.getClass) match {
        case Some(method) => method.invoke(runnable).asInstanceOf[Int]
        case None => 0
      }
  }

  private final class QueuedRunnable(
      runnable: Runnable,
      val priority: Int,
      val submissionIndex: Long
  ) extends Runnable
      with Comparable[QueuedRunnable] {
    def run(): Unit = runnable.run()

    override def compareTo(other: QueuedRunnable): Int =
      priority.compareTo(other.priority) match {
        case 0 => submissionIndex.compareTo(other.submissionIndex)
        case n => n
      }
  }

  private final class PriorityThreadPoolExecutor(
      threadCount: Int,
      threadFactory: ThreadFactory
  ) extends ThreadPoolExecutor(
        threadCount,
        threadCount,
        60 * 1000,
        TimeUnit.SECONDS,
        new PriorityBlockingQueue[Runnable](),
        threadFactory
      ) {
    private val submissionCount = new java.util.concurrent.atomic.AtomicLong()

    override def execute(command: Runnable): Unit =
      super.execute(
        new QueuedRunnable(
          runnable = command,
          priority = RunnablePriority(command),
          submissionIndex = submissionCount.getAndIncrement()
        )
      )
  }

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
      val submitterPwd = os.dynamicPwdFunction.value
      val submitterChecker = os.checker.value
      val submitterStreams = new mill.api.SystemStreams(Console.out, Console.err, System.in)
      executor.execute(new PriorityRunnable(
        0,
        () =>
          os.checker.withValue(submitterChecker) {
            os.dynamicPwdFunction.withValue(() => submitterPwd()) {
              mill.api.SystemStreamsUtils.withStreams(submitterStreams) {
                runnable.run()
              }
            }
          }
      ))

    }

    def reportFailure(t: Throwable): Unit = {}
    def close(): Unit = executor.shutdown()

    /**
     * Subclass of [[java.lang.Runnable]] that assigns a priority to execute it
     *
     * Priority 0 is the default priority of all Mill task, priorities <0 can be used to
     * prioritize this runnable over most other tasks, while priorities >0 can be used to
     * de-prioritize it.
     */
    class PriorityRunnable(val priority: Int, run0: () => Unit) extends Runnable {
      def run(): Unit = run0()
    }

    /**
     * A variant of `scala.concurrent.Future{...}` that sets the `pwd` to a different
     * folder [[dest]] and duplicates the logging streams to [[dest]].log while evaluating
     * [[t]], to avoid conflict with other tasks that may be running concurrently
     */
    def async[T](dest: Path, key: String, message: String, priority: Int)(t: Logger => T)(using
        ctx: mill.api.TaskCtx
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
      val submitterChecker = os.checker.value
      val promise = concurrent.Promise[T]
      val runnable = new PriorityRunnable(
        priority = priority,
        run0 = () => {
          val result = NonFatal.Try(logger.withPromptLine {
            os.checker.withValue(submitterChecker) {
              os.dynamicPwdFunction.withValue(() => makeDest()) {
                mill.api.SystemStreamsUtils.withStreams(logger.streams) {
                  t(logger)
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
    val threadFactory: ThreadFactory = runnable => {
      val threadIndex = threadCounter.incrementAndGet()
      val t = new Thread(
        runnable,
        s"execution-contexts-threadpool-$executorIndex-thread-$threadIndex"
      )
      t.setDaemon(true)
      t
    }
    new PriorityThreadPoolExecutor(threadCount, threadFactory)
  }
}
