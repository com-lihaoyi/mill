package mill.contrib.jmh

import org.openjdk.jmh.annotations._

object Bench1States {

  @State(Scope.Benchmark)
  class BenchmarkState {
    @volatile
    var x = Math.PI
  }

  @State(Scope.Thread)
  class ThreadState {
    @volatile
    var x = Math.PI
  }
}

@BenchmarkMode(Array(Mode.All))
class Bench1 {

  import Bench1States._

  @Benchmark
  def measureShared(state: BenchmarkState) = {
    state.x += 1
  }

  @Benchmark
  def measureUnshared(state: ThreadState) = {
    state.x += 1
  }
}
