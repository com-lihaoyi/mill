package mill.define

final class Lazy[T](t: () => T) {
  lazy val value: T = t()
}
