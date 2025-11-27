package mill.scalajslib.worker

import utest.*

import java.util.concurrent.atomic.AtomicInteger

class ParallelismLimiterTests extends TestSuite {

  override def tests = Tests {
    test("limitedoJobs") {

      val maxJobs = 3
      val limiter = ParallelismLimiter(maxJobs)

      val concurrentCount = new AtomicInteger(0)
      val maxObserved = new AtomicInteger(0)

      def work(i: Int, workTimeMs: Int): Unit = {
        val before = concurrentCount.incrementAndGet()
        maxObserved.updateAndGet(v => Math.max(v, before))

        Thread.sleep(workTimeMs)

        val after = concurrentCount.decrementAndGet()
        assert(after >= 0)
      }

      val tasks = (1 to 10).map { i =>
        new Thread(() =>
          limiter.runLimited {
            work(i, 50)
          }
        )
      }

      tasks.foreach(_.start())
      tasks.foreach(_.join())

      assert(maxObserved.get() <= maxJobs)
    }

  }

}
