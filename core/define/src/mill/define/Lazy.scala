package mill.define

class Lazy[T](t: () => T) {
  lazy val value: T = t()
}
