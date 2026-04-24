package mill.internal

import mill.api.daemon.internal.LauncherLocking
import mill.api.daemon.internal.LauncherLocking.LockKind
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}

object FairRwLockTests extends TestSuite {
  val tests: Tests = Tests {
    test("allows-concurrent-reads") {
      val waitingBytes = new ByteArrayOutputStream()
      val waitingErr = new PrintStream(waitingBytes)
      val lock = new FairRwLock("concurrent-reads")

      val first = lock.acquire(LockKind.Read, waitingErr, noWait = false)
      val secondAcquired = new CountDownLatch(1)
      val secondLeaseRef = new AtomicReference[LauncherLocking.Lease]()

      val secondThread = new Thread(() => {
        secondLeaseRef.set(lock.acquire(LockKind.Read, waitingErr, noWait = false))
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
      val lock = new FairRwLock("write-exclusion")

      val first = lock.acquire(LockKind.Write, waitingErr, noWait = false)
      val secondAcquired = new CountDownLatch(1)
      val secondLeaseRef = new AtomicReference[LauncherLocking.Lease]()

      val secondThread = new Thread(() => {
        secondLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false))
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
      val lock = new FairRwLock("reader-writer-fairness")

      val firstReader = lock.acquire(LockKind.Read, waitingErr, noWait = false)
      val writerAcquired = new CountDownLatch(1)
      val releaseWriter = new CountDownLatch(1)
      val secondReaderAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val secondReaderLeaseRef = new AtomicReference[LauncherLocking.Lease]()

      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false))
        writerAcquired.countDown()
        releaseWriter.await(5, TimeUnit.SECONDS)
        writerLeaseRef.get().close()
      })
      writerThread.start()

      assertEventually(waitingBytes.size() > 0)

      val secondReaderThread = new Thread(() => {
        secondReaderLeaseRef.set(lock.acquire(LockKind.Read, waitingErr, noWait = false))
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
      val lock = new FairRwLock("cross-thread-write-close")

      val acquiredLease = new AtomicReference[LauncherLocking.Lease]()
      val writerReady = new CountDownLatch(1)
      val writerThread = new Thread(() => {
        acquiredLease.set(lock.acquire(LockKind.Write, waitingErr, noWait = false))
        writerReady.countDown()
      })
      writerThread.start()
      assert(writerReady.await(5, TimeUnit.SECONDS))

      val secondWriterAcquired = new CountDownLatch(1)
      val secondWriterLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val secondWriterThread = new Thread(() => {
        secondWriterLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false))
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
      val lock = new FairRwLock("cross-thread-read-close")

      val acquiredLease = new AtomicReference[LauncherLocking.Lease]()
      val readerReady = new CountDownLatch(1)
      val readerThread = new Thread(() => {
        acquiredLease.set(lock.acquire(LockKind.Read, waitingErr, noWait = false))
        readerReady.countDown()
      })
      readerThread.start()
      assert(readerReady.await(5, TimeUnit.SECONDS))

      val writerAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(LockKind.Write, waitingErr, noWait = false))
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
  }

  private def assertEventually(predicate: => Boolean): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!predicate && System.nanoTime() < deadline) Thread.sleep(10)
    if (!predicate) throw new java.lang.AssertionError("predicate did not become true")
  }
}
