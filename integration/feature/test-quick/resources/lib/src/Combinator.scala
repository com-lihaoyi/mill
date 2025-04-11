package lib

trait Combinator[T] {
  def combine(a: T, b: T): T
  def combine2(a: T, b: T, c: T): T = combine(combine(a, b), c)
}
