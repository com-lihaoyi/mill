package mill.androidlib

import mill.api.Task
import mill.client.lock.{Lock, TryLocked}

import java.util.concurrent.Executors

/**
 * A worker that executes one thing at a time, using the provided lock
 */
@mill.api.experimental
class AndroidSdkWorker(androidSdkManagerLockFile: os.Path, maxAttempts: Int) extends AutoCloseable {

  val workerThread = Executors.newSingleThreadExecutor()

  val lock = Lock.file(androidSdkManagerLockFile.toString)

  private def acquireLock(using ctx: mill.api.TaskCtx): TryLocked = {
    var attempts = 0
    val maxAttempts = 10
    var tryLock: TryLocked = lock.tryLock()
    while (!tryLock.isLocked) {
      tryLock.close()
      attempts += 1
      if (attempts > maxAttempts)
        Task.fail(
          s"Giving up waiting for sdkmanager. Please check if the process is slow or stuck or if the ${androidSdkManagerLockFile} is held by another process!"
        )
      Task.log.info(
        s"Waiting for sdkmanager lock ($attempts/$maxAttempts)... Trying again in 5 seconds!"
      )
      Thread.sleep(5000)
      tryLock = lock.tryLock()
    }
    tryLock
  }

  def process[A](f: () => A)(using ctx: mill.api.TaskCtx): A = {
    workerThread.submit(() => scala.util.Using.resource(acquireLock)(_ => f())).get()
  }

  override def close(): Unit = workerThread.close()
}
