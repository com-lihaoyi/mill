package mill.api.daemon.internal

trait NamedTaskApi[+T] extends TaskApi[T] {
  def label: String
}
