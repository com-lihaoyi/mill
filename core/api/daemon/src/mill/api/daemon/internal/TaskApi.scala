package mill.api.daemon.internal

trait TaskApi[+T] {
  def apply(): T
}
