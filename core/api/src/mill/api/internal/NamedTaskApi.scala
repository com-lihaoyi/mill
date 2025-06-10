package mill.api.internal

trait NamedTaskApi[+T] extends TaskApi[T] {
  def label: String
}
