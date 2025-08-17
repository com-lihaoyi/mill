package mill.util

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

private[mill] object Timed {
  case class Result[+A](result: A, duration: FiniteDuration) {
    def durationPretty: String = duration.toMillis + "ms"
  }

  /** Computes how long it took to compute `f`. */
  def apply[A](f: => A): Result[A] = {
    val start = System.nanoTime()
    val result = f
    val duration = FiniteDuration(System.nanoTime() - start, TimeUnit.NANOSECONDS)
    Result(result, duration)
  }
}
