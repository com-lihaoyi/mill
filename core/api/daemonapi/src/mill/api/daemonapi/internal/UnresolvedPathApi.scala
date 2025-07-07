package mill.api.daemonapi.internal

trait UnresolvedPathApi[P] {
  def resolve(outPath: P): P
}
