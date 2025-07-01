package mill.api

final class Lazy[T](t: () => T) {
  lazy val value: T = t()
}
