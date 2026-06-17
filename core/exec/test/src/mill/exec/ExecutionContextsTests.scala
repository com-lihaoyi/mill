package mill.exec

import utest.*

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future, Promise}

object ExecutionContextsTests extends TestSuite {
  val tests = Tests {
    test("blockingScalesPoolUpThenBackDownToBaseline") {
      // Managed-blocking grows BOTH core and max while a task is parked, then
      // scales both back down on exit so the `--jobs` parallelism bound is
      // restored (no leftover workers left draining the queue). The resulting
      // thread churn is intentional and is kept out of chrome profiles by
      // `mill.internal.ThreadNumberer` recycling ids of reaped threads.
      val executor = ExecutionContexts.createExecutor(threadCount = 2)
      val pool = ExecutionContexts.ThreadPool(executor)
      try {
        assert(executor.getCorePoolSize == 2 && executor.getMaximumPoolSize == 2)

        pool.enterBlocking()
        assert(executor.getCorePoolSize == 3 && executor.getMaximumPoolSize == 3)

        pool.enterBlocking()
        assert(executor.getCorePoolSize == 4 && executor.getMaximumPoolSize == 4)

        pool.leaveBlocking()
        assert(executor.getCorePoolSize == 3 && executor.getMaximumPoolSize == 3)

        pool.leaveBlocking()
        assert(executor.getCorePoolSize == 2 && executor.getMaximumPoolSize == 2)

        assert(executor.getKeepAliveTime(TimeUnit.SECONDS) == 60)
      } finally executor.shutdown()
    }

    test("blockingRunsCompensationWorkerWhileParked") {
      // With both core threads occupied (one by a long-running blocker, one by a
      // parent that parks in `blocking{...}`), a child task must still make
      // progress — which is only possible if `blocking{...}` grew the pool so a
      // compensation worker could run it.
      val executor = ExecutionContexts.createExecutor(threadCount = 2)
      val pool = ExecutionContexts.ThreadPool(executor)
      val blockerStarted = CountDownLatch(1)
      val releaseBlocker = CountDownLatch(1)

      try {
        pool.execute(() => {
          blockerStarted.countDown()
          releaseBlocker.await()
        })
        assert(blockerStarted.await(5, TimeUnit.SECONDS))

        val result = Promise[String]()
        pool.execute(() => {
          val childThread = pool.blocking {
            Await.result(
              Future(Thread.currentThread().getName)(using pool),
              5.seconds
            )
          }
          result.success(childThread)
        })

        assert(Await.result(result.future, 5.seconds) != null)
      } finally {
        releaseBlocker.countDown()
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          executor.shutdownNow()
          executor.awaitTermination(5, TimeUnit.SECONDS)
        }
      }
    }
  }
}
