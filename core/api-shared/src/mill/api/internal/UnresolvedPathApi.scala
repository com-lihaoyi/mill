package mill.api.internal

trait UnresolvedPathApi[P] {
  def resolve(outPath: P): P
}
