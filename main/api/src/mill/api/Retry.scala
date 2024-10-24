package mill.api

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

case class Retry(
    count: Int = 5,
    backoffMillis: Long = 10,
    backoffMultiplier: Double = 2.0,
    timeoutMillis: Long = -1,
    filter: (Int, Throwable) => Boolean = (_, _) => true
) {

  /**
   * Generic retry functionality
   *
   * @param count How many times to retry before giving up
   * @param backoffMillis What is the initial backoff time
   * @param backoffMultiplier How much to multiply the initial backoff each time
   * @param timeoutMillis How much time we want to allow [[t]] to run. If passed,
   *                      runs [[t]] in a separate thread and throws a `TimeoutException`
   *                      if it takes too long
   * @param filter Whether or not we want to retry a given exception at a given retryCount;
   *               defaults to `true` to retry all exceptions, but can be made more fine-grained
   *               to only retry specific exceptions, or log them together with the retryCount
   * @param t The code block that we want to retry
   * @return the value of evaluating [[t]], or throws an exception if evaluating
   *         [[t]] fails more than [[count]] times
   */
  def apply[T](t: => T): T = {
    indexed(i => t)
  }

  def indexed[T](t: Int => T): T = {
    def rec(retryCount: Int, currentBackoffMillis: Long): T = {
      try {
        if (timeoutMillis == -1) t(retryCount)
        else {
          val result = Promise[T]
          val thread = new Thread({ () =>
            result.complete(scala.util.Try(t(retryCount)))
          }: Runnable)
          thread.start()
          Await.result(result.future, Duration.apply(timeoutMillis, TimeUnit.MILLISECONDS))
        }
      } catch {
        case e: Throwable if retryCount < count && filter(retryCount + 1, e) =>
          Thread.sleep(currentBackoffMillis)
          rec(retryCount + 1, (currentBackoffMillis * backoffMultiplier).toInt)
      }
    }

    rec(0, backoffMillis)
  }
}
