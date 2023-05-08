package mill.api

class Lazy[T](t: () => T) {
  lazy val value = t()
}
