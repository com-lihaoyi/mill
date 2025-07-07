package mill.api.daemonapi.internal

trait NamedTaskApi[+T] extends TaskApi[T] {
  def label: String
}
