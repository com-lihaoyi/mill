package mill.api.shared.internal

trait TaskApi[+T] {
  def apply(): T
}
