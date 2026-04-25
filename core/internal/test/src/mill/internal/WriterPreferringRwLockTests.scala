package mill.internal

import mill.api.daemon.internal.LauncherLocking
import mill.api.daemon.internal.LauncherLocking.{HolderInfo, LockKind}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}

object WriterPreferringRwLockTests extends TestSuite {
  private val testHolder = HolderInfo(pid = 1234, command = "unit-test")

  val tests: Tests = Tests {
    test("allows-concurrent-reads") {
      val waitingBytes = new ByteArrayOutputStream()
      val waitingErr = new PrintStream(waitingBytes)
      val lock = new WriterPreferringRwLock("concurrent-reads")

      val first = lock.acquire(LockKind.Read, waitingErr, noWait = false, testHolder)
      val secondAcquired = new CountDownLatch(1)
      val secondLeaseRef = new AtomicReference[LauncherLocking.Lease]()

      val secondThread = new Thread(() => {
        secondLeaseRef.set(lock.acquire(LockKind.Read, waitingErr, noWait = false, testHolder))
        secondAcquired.countDown()
      })
      secondThread.start()

      assert(secondAcquired.await(1, TimeUnit.SECONDS))
      assert(waitingBytes.size() == 0)

      secondLeaseRef.get().close()
      first.close()
      secondThread.join(3000)
    }

    test("enforces-write-exclusion") {
      val waitingBytes = new ByteArrayOutputStream()
      val waitingErr = new PrintStream(waitingBytes)
      val lock = new WriterPreferringRwLock("write-exclusion")

      val first = lock.acquire(LockKind.Write, waitingErr, noWait = false, testHolder)
      val secondAcquired = new CountDownLatch(1)
      val secondLeaseRef = new AtomicReference[LauncherLocking.Lease]()

      val secondThread = new Thread(() => {
        secondLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false, testHolder))
        secondAcquired.countDown()
      })
      secondThread.start()

      assertEventually(waitingBytes.size() > 0)
      assert(!secondAcquired.await(100, TimeUnit.MILLISECONDS))

      first.close()
      assert(secondAcquired.await(5, TimeUnit.SECONDS))

      secondLeaseRef.get().close()
      secondThread.join(3000)
    }

    test("enforces-read-write-exclusion-and-writer-priority") {
      val waitingBytes = new ByteArrayOutputStream()
      val waitingErr = new PrintStream(waitingBytes)
      val lock = new WriterPreferringRwLock("reader-writer-fairness")

      val firstReader = lock.acquire(LockKind.Read, waitingErr, noWait = false, testHolder)
      val writerAcquired = new CountDownLatch(1)
      val releaseWriter = new CountDownLatch(1)
      val secondReaderAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val secondReaderLeaseRef = new AtomicReference[LauncherLocking.Lease]()

      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false, testHolder))
        writerAcquired.countDown()
        releaseWriter.await(5, TimeUnit.SECONDS)
        writerLeaseRef.get().close()
      })
      writerThread.start()

      assertEventually(waitingBytes.size() > 0)

      val secondReaderThread = new Thread(() => {
        secondReaderLeaseRef.set(lock.acquire(
          LockKind.Read,
          waitingErr,
          noWait = false,
          testHolder
        ))
        secondReaderAcquired.countDown()
      })
      secondReaderThread.start()

      firstReader.close()

      assert(writerAcquired.await(5, TimeUnit.SECONDS))
      assert(!secondReaderAcquired.await(100, TimeUnit.MILLISECONDS))

      releaseWriter.countDown()
      assert(secondReaderAcquired.await(5, TimeUnit.SECONDS))

      secondReaderLeaseRef.get().close()
      writerThread.join(3000)
      secondReaderThread.join(3000)
    }

    test("write-lease-can-be-closed-on-a-different-thread") {
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new WriterPreferringRwLock("cross-thread-write-close")

      val acquiredLease = new AtomicReference[LauncherLocking.Lease]()
      val writerReady = new CountDownLatch(1)
      val writerThread = new Thread(() => {
        acquiredLease.set(lock.acquire(LockKind.Write, waitingErr, noWait = false, testHolder))
        writerReady.countDown()
      })
      writerThread.start()
      assert(writerReady.await(5, TimeUnit.SECONDS))

      val secondWriterAcquired = new CountDownLatch(1)
      val secondWriterLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val secondWriterThread = new Thread(() => {
        secondWriterLeaseRef.set(lock.acquire(
          LockKind.Write,
          waitingErr,
          noWait = false,
          testHolder
        ))
        secondWriterAcquired.countDown()
      })
      secondWriterThread.start()

      assert(!secondWriterAcquired.await(100, TimeUnit.MILLISECONDS))

      val closingThread = new Thread(() => acquiredLease.get().close())
      closingThread.start()
      closingThread.join(3000)

      assert(secondWriterAcquired.await(5, TimeUnit.SECONDS))

      secondWriterLeaseRef.get().close()
      writerThread.join(3000)
      secondWriterThread.join(3000)
    }

    test("read-lease-can-be-closed-on-a-different-thread") {
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new WriterPreferringRwLock("cross-thread-read-close")

      val acquiredLease = new AtomicReference[LauncherLocking.Lease]()
      val readerReady = new CountDownLatch(1)
      val readerThread = new Thread(() => {
        acquiredLease.set(lock.acquire(LockKind.Read, waitingErr, noWait = false, testHolder))
        readerReady.countDown()
      })
      readerThread.start()
      assert(readerReady.await(5, TimeUnit.SECONDS))

      val writerAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false, testHolder))
        writerAcquired.countDown()
      })
      writerThread.start()

      assert(!writerAcquired.await(100, TimeUnit.MILLISECONDS))

      val closingThread = new Thread(() => acquiredLease.get().close())
      closingThread.start()
      closingThread.join(3000)

      assert(writerAcquired.await(5, TimeUnit.SECONDS))

      writerLeaseRef.get().close()
      readerThread.join(3000)
      writerThread.join(3000)
    }

    test("downgraded-lease-can-be-closed-on-a-different-thread") {
      // Real usage pattern: a task acquires write, downgrades to read on its
      // worker thread, then the read lease is closed later by RunnerLauncherState
      // cleanup on a different thread.
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new WriterPreferringRwLock("downgrade-cross-thread-close")

      val leaseRef = new AtomicReference[LauncherLocking.Lease]()
      val acquireThread = new Thread(() => {
        val l = lock.acquire(LockKind.Write, waitingErr, noWait = false, testHolder)
        l.downgradeToRead()
        leaseRef.set(l)
      })
      acquireThread.start()
      acquireThread.join(3000)

      val writerAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false, testHolder))
        writerAcquired.countDown()
      })
      writerThread.start()

      assert(!writerAcquired.await(100, TimeUnit.MILLISECONDS))

      val closingThread = new Thread(() => leaseRef.get().close())
      closingThread.start()
      closingThread.join(3000)

      assert(writerAcquired.await(5, TimeUnit.SECONDS))

      writerLeaseRef.get().close()
      writerThread.join(3000)
    }

    test("waiting-message-names-the-current-holder") {
      // Regression test: the waiting message must include the blocking
      // launcher's command and PID so users (and integration tests) can
      // identify what's blocking them.
      val waitingBytes = new ByteArrayOutputStream()
      val waitingErr = new PrintStream(waitingBytes)
      val lock = new WriterPreferringRwLock("holder-naming")

      val blocker = HolderInfo(pid = 9876, command = "blockerTask")
      val firstLease = lock.acquire(LockKind.Write, waitingErr, noWait = false, blocker)

      val secondAcquired = new CountDownLatch(1)
      val secondLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val waiter = HolderInfo(pid = 5555, command = "waiterTask")
      val secondThread = new Thread(() => {
        secondLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false, waiter))
        secondAcquired.countDown()
      })
      secondThread.start()

      assertEventually(waitingBytes.size() > 0)
      val msg = waitingBytes.toString
      assert(msg.contains("'blockerTask'"))
      assert(msg.contains("PID 9876"))
      assert(msg.contains("waiting for it to be done..."))

      firstLease.close()
      assert(secondAcquired.await(5, TimeUnit.SECONDS))
      secondLeaseRef.get().close()
      secondThread.join(3000)
    }

    test("writer-is-not-starved-by-steady-reader-stream") {
      // Regression test for the prior fairness bug: two concurrent writers
      // could both observe "available" and neither register as a waiter; the
      // second writer then waits with waitingWriters == 0, so a steady stream
      // of readers could jump the queue and starve it. With the merged
      // acquire path, either writer is either reserved immediately or
      // registered as a waiter.
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new WriterPreferringRwLock("writer-starvation")

      // Hold a reader so neither writer can acquire immediately.
      val firstReader = lock.acquire(LockKind.Read, waitingErr, noWait = false, testHolder)

      val writerAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false, testHolder))
        writerAcquired.countDown()
      })
      writerThread.start()

      // Give the writer time to register as waiting.
      Thread.sleep(50)

      // Now spawn a steady stream of readers. With waitingWriters incremented,
      // each of these should observe canAcquireRead = false and queue.
      val readerThreads = (1 to 20).map(_ => {
        val t = new Thread(() => {
          val r = lock.acquire(LockKind.Read, waitingErr, noWait = false, testHolder)
          Thread.sleep(10)
          r.close()
        })
        t.start()
        t
      })

      // Release the initial reader. Writer should win the next acquisition.
      firstReader.close()

      assert(writerAcquired.await(5, TimeUnit.SECONDS))

      writerLeaseRef.get().close()
      readerThreads.foreach(_.join(5000))
      writerThread.join(3000)
    }

    test("no-wait-fails-with-holder-info-in-message") {
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new WriterPreferringRwLock("no-wait")
      val blocker = HolderInfo(pid = 7777, command = "blockerCmd")
      val first = lock.acquire(LockKind.Write, waitingErr, noWait = false, blocker)

      val waiter = HolderInfo(pid = 1111, command = "waiterCmd")
      val ex =
        try {
          lock.acquire(LockKind.Write, waitingErr, noWait = true, waiter)
          throw new java.lang.AssertionError("expected no-wait acquisition to fail")
        } catch {
          case e: Exception => e
        }
      assert(ex.getMessage.contains("'blockerCmd'"))
      assert(ex.getMessage.contains("PID 7777"))
      assert(ex.getMessage.contains("--no-wait"))

      first.close()
    }

    test("waiting-message-names-a-still-holding-reader-not-a-released-one") {
      // Regression test for the lastHolder bug: if two readers coexist and
      // the more-recent acquirer releases first, a subsequent writer that
      // blocks must be told about the STILL-HOLDING older reader, not about
      // the already-released most-recent acquirer.
      //
      // With a single `lastHolder` field, releasing the most-recent holder
      // left that field pointing at the released launcher. A new writer's
      // waiting message then reported the wrong PID (sometimes the waiter's
      // own — the same launcher that just released — which made it look like
      // the launcher was blocked by itself).
      val waitingBytes = new ByteArrayOutputStream()
      val waitingErr = new PrintStream(waitingBytes)
      val lock = new WriterPreferringRwLock("stale-last-holder")

      val stillHolding = HolderInfo(pid = 1111, command = "stillHoldingCmd")
      val alreadyReleased = HolderInfo(pid = 2222, command = "alreadyReleasedCmd")
      val waiter = HolderInfo(pid = 3333, command = "waiterCmd")

      val firstReader = lock.acquire(LockKind.Read, waitingErr, noWait = false, stillHolding)
      val secondReader = lock.acquire(LockKind.Read, waitingErr, noWait = false, alreadyReleased)
      // Release the MORE RECENT reader. Only `stillHolding` remains active.
      secondReader.close()

      // New writer arrives. It must block (readerCount > 0) and emit a
      // waiting message that names the still-holding reader — NOT the one
      // that just released.
      val writerAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false, waiter))
        writerAcquired.countDown()
      })
      writerThread.start()

      assertEventually(waitingBytes.size() > 0)
      val msg = waitingBytes.toString
      assert(msg.contains("'stillHoldingCmd'"))
      assert(msg.contains("PID 1111"))
      assert(!msg.contains("'alreadyReleasedCmd'"))
      assert(!msg.contains("PID 2222"))

      firstReader.close()
      assert(writerAcquired.await(5, TimeUnit.SECONDS))
      writerLeaseRef.get().close()
      writerThread.join(3000)
    }
  }

  private def assertEventually(predicate: => Boolean): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!predicate && System.nanoTime() < deadline) Thread.sleep(10)
    if (!predicate) throw new java.lang.AssertionError("predicate did not become true")
  }
}
