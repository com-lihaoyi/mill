package mill.scalajslib.worker

import java.util.concurrent.Semaphore

/**
 * Limit the parallelism of jobs run via [[runLimited]].
 * @param maxJobs The maximal parallelism
 */
class ParallelismLimiter(maxJobs: Int) {

  private val linkerJobsSemaphore = Semaphore(maxJobs)

  def runLimited[T](thunk: => T): T = {
    linkerJobsSemaphore.acquire()
    try {
      thunk
    } finally {
      linkerJobsSemaphore.release()
    }
  }

}
