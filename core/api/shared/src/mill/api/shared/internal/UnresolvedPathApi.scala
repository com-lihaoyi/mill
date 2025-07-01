package mill.api.shared.internal

trait UnresolvedPathApi[P] {
  def resolve(outPath: P): P
}
