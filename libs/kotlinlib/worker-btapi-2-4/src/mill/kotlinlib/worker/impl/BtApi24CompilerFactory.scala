package mill.kotlinlib.worker.impl

class BtApi24CompilerFactory extends BtApiCompilerFactory {
  def create(classpathSnapshotCache: os.Path): BtApiCompiler =
    JvmCompileBtApi24Impl(classpathSnapshotCache)
}
