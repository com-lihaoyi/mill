package mill.internal

class Lazy[T](t: () => T) {
  lazy val value: T = t()
}
