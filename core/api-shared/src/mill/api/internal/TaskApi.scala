package mill.api.internal

trait TaskApi[+T] {
  def apply(): T
}
