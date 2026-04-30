package mill.internal

import mill.api.daemon.internal.LauncherLocking
import mill.api.daemon.internal.LauncherLocking.LockKind
import mill.internal.CrossThreadRwLock.HolderInfo
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}

object CrossThreadRwLockTests extends TestSuite {
  private val testHolder = HolderInfo(pid = 1234, command = "unit-test")

  val tests: Tests = Tests {
    test("allows-concurrent-reads") {
      val waitingBytes = new ByteArrayOutputStream()
      val waitingErr = new PrintStream(waitingBytes)
      val lock = new CrossThreadRwLock("concurrent-reads")

      val first = lock.acquire(
        LockKind.Read,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = false,
        testHolder
      )
      val secondAcquired = new CountDownLatch(1)
      val secondLeaseRef = new AtomicReference[LauncherLocking.Lease]()

      val secondThread = new Thread(() => {
        secondLeaseRef.set(lock.acquire(
          LockKind.Read,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          testHolder
        ))
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
      val lock = new CrossThreadRwLock("write-exclusion")

      val first = lock.acquire(
        LockKind.Write,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = false,
        testHolder
      )
      val secondAcquired = new CountDownLatch(1)
      val secondLeaseRef = new AtomicReference[LauncherLocking.Lease]()

      val secondThread = new Thread(() => {
        secondLeaseRef.set(lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          testHolder
        ))
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
      val lock = new CrossThreadRwLock("reader-writer-fairness")

      val firstReader = lock.acquire(
        LockKind.Read,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = false,
        testHolder
      )
      val writerAcquired = new CountDownLatch(1)
      val releaseWriter = new CountDownLatch(1)
      val secondReaderAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val secondReaderLeaseRef = new AtomicReference[LauncherLocking.Lease]()

      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          testHolder
        ))
        writerAcquired.countDown()
        releaseWriter.await(5, TimeUnit.SECONDS)
        writerLeaseRef.get().close()
      })
      writerThread.start()

      assertEventually(waitingBytes.size() > 0)

      val secondReaderThread = new Thread(() => {
        secondReaderLeaseRef.set(lock.acquire(
          LockKind.Read,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
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
      val lock = new CrossThreadRwLock("cross-thread-write-close")

      val acquiredLease = new AtomicReference[LauncherLocking.Lease]()
      val writerReady = new CountDownLatch(1)
      val writerThread = new Thread(() => {
        acquiredLease.set(lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          testHolder
        ))
        writerReady.countDown()
      })
      writerThread.start()
      assert(writerReady.await(5, TimeUnit.SECONDS))

      val secondWriterAcquired = new CountDownLatch(1)
      val secondWriterLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val secondWriterThread = new Thread(() => {
        secondWriterLeaseRef.set(lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
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
      val lock = new CrossThreadRwLock("cross-thread-read-close")

      val acquiredLease = new AtomicReference[LauncherLocking.Lease]()
      val readerReady = new CountDownLatch(1)
      val readerThread = new Thread(() => {
        acquiredLease.set(lock.acquire(
          LockKind.Read,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          testHolder
        ))
        readerReady.countDown()
      })
      readerThread.start()
      assert(readerReady.await(5, TimeUnit.SECONDS))

      val writerAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          testHolder
        ))
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
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new CrossThreadRwLock("downgrade-cross-thread-close")

      val leaseRef = new AtomicReference[LauncherLocking.Lease]()
      val acquireThread = new Thread(() => {
        val l = lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          testHolder
        )
        l.downgradeToRead()
        leaseRef.set(l)
      })
      acquireThread.start()
      acquireThread.join(3000)

      val writerAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          testHolder
        ))
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

    test("lock-upgrade-closes-retained-read-lease-on-read-exception") {
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new CrossThreadRwLock("read-retain-exception")

      assertThrows(classOf[RuntimeException]) {
        LockUpgrade.readThenWrite(
          acquireRead = lock.acquire(
            LockKind.Read,
            mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
            noWait = false,
            testHolder
          ),
          tryAcquireWrite = () => lock.tryAcquireWrite(testHolder),
          awaitStateChange = lock.awaitStateChange,
          waitReporter = mill.api.daemon.internal.LauncherLocking.WaitReporter.Noop
        ) { scope =>
          scope.retain()
          throw new RuntimeException("boom")
        }(_ => ())
      }

      val writer = lock.acquire(
        LockKind.Write,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = true,
        testHolder
      )
      writer.close()
    }

    test("lock-upgrade-closes-retained-read-lease-on-invalid-escalation") {
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new CrossThreadRwLock("read-retain-escalate")

      assertThrows(classOf[IllegalStateException]) {
        LockUpgrade.readThenWrite(
          acquireRead = lock.acquire(
            LockKind.Read,
            mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
            noWait = false,
            testHolder
          ),
          tryAcquireWrite = () => lock.tryAcquireWrite(testHolder),
          awaitStateChange = lock.awaitStateChange,
          waitReporter = mill.api.daemon.internal.LauncherLocking.WaitReporter.Noop
        ) { scope =>
          scope.retain()
          LockUpgrade.Decision.Escalate
        }(_ => ())
      }

      val writer = lock.acquire(
        LockKind.Write,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = true,
        testHolder
      )
      writer.close()
    }

    test("lock-upgrade-closes-downgraded-write-lease-on-write-exception") {
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new CrossThreadRwLock("write-downgrade-exception")

      assertThrows(classOf[RuntimeException]) {
        LockUpgrade.readThenWrite(
          acquireRead = lock.acquire(
            LockKind.Read,
            mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
            noWait = false,
            testHolder
          ),
          tryAcquireWrite = () => lock.tryAcquireWrite(testHolder),
          awaitStateChange = lock.awaitStateChange,
          waitReporter = mill.api.daemon.internal.LauncherLocking.WaitReporter.Noop
        )(_ => LockUpgrade.Decision.Escalate) { scope =>
          scope.downgradeAndRetain()
          throw new RuntimeException("boom")
        }
      }

      val writer = lock.acquire(
        LockKind.Write,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = true,
        testHolder
      )
      writer.close()
    }

    test("waiting-message-names-the-current-holder") {
      val waitingBytes = new ByteArrayOutputStream()
      val waitingErr = new PrintStream(waitingBytes)
      val lock = new CrossThreadRwLock("holder-naming")

      val blocker = HolderInfo(pid = 9876, command = "blockerTask")
      val firstLease = lock.acquire(
        LockKind.Write,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = false,
        blocker
      )

      val secondAcquired = new CountDownLatch(1)
      val secondLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val waiter = HolderInfo(pid = 5555, command = "waiterTask")
      val secondThread = new Thread(() => {
        secondLeaseRef.set(lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          waiter
        ))
        secondAcquired.countDown()
      })
      secondThread.start()

      assertEventually(waitingBytes.size() > 0)
      val msg = waitingBytes.toString
      assert(msg.contains("(blockerTask)"))
      assert(msg.contains("'holder-naming'"))
      assert(msg.contains("PID 9876"))
      assert(msg.contains("blocked taking write lock"))

      firstLease.close()
      assert(secondAcquired.await(5, TimeUnit.SECONDS))
      secondLeaseRef.get().close()
      secondThread.join(3000)
    }

    test("writer-is-not-starved-by-steady-reader-stream") {
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new CrossThreadRwLock("writer-starvation")

      val firstReader = lock.acquire(
        LockKind.Read,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = false,
        testHolder
      )

      val writerAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          testHolder
        ))
        writerAcquired.countDown()
      })
      writerThread.start()

      Thread.sleep(50)

      val readerThreads = (1 to 20).map(_ => {
        val t = new Thread(() => {
          val r = lock.acquire(
            LockKind.Read,
            mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
            noWait = false,
            testHolder
          )
          Thread.sleep(10)
          r.close()
        })
        t.start()
        t
      })

      firstReader.close()

      assert(writerAcquired.await(5, TimeUnit.SECONDS))

      writerLeaseRef.get().close()
      readerThreads.foreach(_.join(5000))
      writerThread.join(3000)
    }

    test("no-wait-fails-with-holder-info-in-message") {
      val waitingErr = new PrintStream(new ByteArrayOutputStream())
      val lock = new CrossThreadRwLock("no-wait")
      val blocker = HolderInfo(pid = 7777, command = "blockerCmd")
      val first = lock.acquire(
        LockKind.Write,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = false,
        blocker
      )

      val waiter = HolderInfo(pid = 1111, command = "waiterCmd")
      val ex =
        try {
          lock.acquire(
            LockKind.Write,
            mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
            noWait = true,
            waiter
          )
          throw new java.lang.AssertionError("expected no-wait acquisition to fail")
        } catch {
          case e: Exception => e
        }
      assert(ex.getMessage.contains("(blockerCmd)"))
      assert(ex.getMessage.contains("'no-wait'"))
      assert(ex.getMessage.contains("PID 7777"))
      assert(ex.getMessage.contains("--no-wait"))

      first.close()
    }

    test("waiting-message-names-a-still-holding-reader-not-a-released-one") {
      val waitingBytes = new ByteArrayOutputStream()
      val waitingErr = new PrintStream(waitingBytes)
      val lock = new CrossThreadRwLock("stale-last-holder")

      val stillHolding = HolderInfo(pid = 1111, command = "stillHoldingCmd")
      val alreadyReleased = HolderInfo(pid = 2222, command = "alreadyReleasedCmd")
      val waiter = HolderInfo(pid = 3333, command = "waiterCmd")

      val firstReader = lock.acquire(
        LockKind.Read,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = false,
        stillHolding
      )
      val secondReader = lock.acquire(
        LockKind.Read,
        mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
        noWait = false,
        alreadyReleased
      )
      secondReader.close()

      val writerAcquired = new CountDownLatch(1)
      val writerLeaseRef = new AtomicReference[LauncherLocking.Lease]()
      val writerThread = new Thread(() => {
        writerLeaseRef.set(lock.acquire(
          LockKind.Write,
          mill.api.daemon.internal.LauncherLocking.WaitReporter.stderr(waitingErr),
          noWait = false,
          waiter
        ))
        writerAcquired.countDown()
      })
      writerThread.start()

      assertEventually(waitingBytes.size() > 0)
      val msg = waitingBytes.toString
      assert(msg.contains("(stillHoldingCmd)"))
      assert(msg.contains("'stale-last-holder'"))
      assert(msg.contains("PID 1111"))
      assert(!msg.contains("(alreadyReleasedCmd)"))
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

  private def assertThrows[T <: Throwable](clazz: Class[T])(body: => Unit): T = {
    try {
      body
      throw new java.lang.AssertionError(s"expected ${clazz.getName}")
    } catch {
      case t if clazz.isInstance(t) => clazz.cast(t)
    }
  }
}
