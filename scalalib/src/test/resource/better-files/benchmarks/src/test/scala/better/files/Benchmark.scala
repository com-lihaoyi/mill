package better.files

import org.scalatest.FunSuite

trait Benchmark extends FunSuite {
  def profile[A](f: => A): (A, Long) = {
    val t = System.nanoTime()
    (f, ((System.nanoTime() - t)/1e6).toLong)
  }
}
