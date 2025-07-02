package mill.api.shared.internal

trait NamedTaskApi[+T] extends TaskApi[T] {
  def label: String
}
