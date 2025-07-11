package mill.api.daemon.internal

trait UnresolvedPathApi[P] {
  def resolve(outPath: P): P
}
