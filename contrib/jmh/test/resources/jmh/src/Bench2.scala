package mill.contrib.jmh

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
class Bench2 {

  val x = Math.PI

  @Benchmark
  def sqrt: Double = Math.sqrt(x)

  @Benchmark
  def log: Double = Math.log(x)
}
