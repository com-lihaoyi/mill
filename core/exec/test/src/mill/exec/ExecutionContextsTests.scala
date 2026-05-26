package mill.exec

import utest.*

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future, Promise}

object ExecutionContextsTests extends TestSuite {
  val tests = Tests {
    test("blockingReusesCompensationThreads") {
      // Repeated managed-blocking should keep reusing the same compensation
      // worker instead of shrinking maximumPoolSize and retiring an idle worker
      // after every blocked parent task.
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

        val threads = collection.mutable.Set.empty[String]
        for (_ <- 0 until 20) {
          val result = Promise[(String, String)]()
          pool.execute(() => {
            val parentThread = Thread.currentThread().getName
            val childThread = pool.blocking {
              Await.result(
                Future(Thread.currentThread().getName)(using pool),
                5.seconds
              )
            }
            result.success((parentThread, childThread))
          })
          threads.addAll(Await.result(result.future, 5.seconds).productIterator.map(_.toString))

          // Give an executor that shrinks maximumPoolSize on every blocking exit
          // enough time to retire one of the excess workers before the next loop.
          Thread.sleep(10)
        }

        assert(threads.size <= 3)
        assert(executor.getMaximumPoolSize == 3)
        assert(executor.getKeepAliveTime(TimeUnit.SECONDS) == 60)
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
