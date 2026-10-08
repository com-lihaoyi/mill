package mill.kotlinlib.worker.impl

trait BtApiCompilerFactory {
  def create(classpathSnapshotCache: os.Path): BtApiCompiler
}
