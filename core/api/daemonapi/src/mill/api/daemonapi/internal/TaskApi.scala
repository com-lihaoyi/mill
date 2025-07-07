package mill.api.daemonapi.internal

trait TaskApi[+T] {
  def apply(): T
}
