package mill.internal

import utest.*

object ThreadNumbererTests extends TestSuite {

  /** Run `f` on a fresh thread, join it, and return what `f` produced. */
  private def onDeadThread[T](f: Thread => T): (Thread, T) = {
    var result = Option.empty[T]
    var ref = Option.empty[Thread]
    val t = new Thread(() => {
      ref = Some(Thread.currentThread())
      result = Some(f(Thread.currentThread()))
    })
    t.start()
    t.join()
    assert(!t.isAlive)
    (ref.get, result.get)
  }

  val tests = Tests {
    test("liveThreadKeepsStableId") {
      val n = ThreadNumberer()
      val self = Thread.currentThread()
      val id = n.getThreadId(self)
      // Repeated lookups for the same still-live thread are stable.
      assert(n.getThreadId(self) == id)
      assert(n.getThreadId(self) == id)
    }

    test("distinctLiveThreadsGetDistinctIds") {
      val n = ThreadNumberer()
      val a = n.getThreadId(Thread.currentThread())
      // A second thread that is still alive while we number a third must not
      // share its id (no recycling of live threads).
      val barrier = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      var b = -1
      val other = new Thread(() => {
        b = n.getThreadId(Thread.currentThread())
        barrier.countDown()
        release.await()
      })
      other.start()
      barrier.await()
      try assert(b != a)
      finally { release.countDown(); other.join() }
    }

    test("deadThreadIdIsTransferredToNextNewThread") {
      val n = ThreadNumberer()
      // main thread takes id 1
      val mainId = n.getThreadId(Thread.currentThread())
      // a thread that runs then dies takes the next id
      val (_, deadId) = onDeadThread(n.getThreadId)
      assert(deadId != mainId)
      // the next freshly-spawned thread reclaims the dead thread's id rather
      // than minting a new one
      val (_, reusedId) = onDeadThread(n.getThreadId)
      assert(reusedId == deadId)
      // and the still-live main thread's id is untouched throughout
      assert(n.getThreadId(Thread.currentThread()) == mainId)
    }

    test("idsStayCompactUnderChurn") {
      val n = ThreadNumberer()
      n.getThreadId(Thread.currentThread()) // id 1, stays live
      // Many sequential (dead) threads should keep reusing a single recycled id
      // rather than growing the id space, since only one is ever alive at a time.
      val ids = (0 until 50).map(_ => onDeadThread(n.getThreadId)._2).toSet
      assert(ids.size == 1)
    }
  }
}
