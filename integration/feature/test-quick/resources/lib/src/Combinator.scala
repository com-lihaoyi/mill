package lib

trait Combinator[T] {
  def combine(a: T, b: T): T
}
