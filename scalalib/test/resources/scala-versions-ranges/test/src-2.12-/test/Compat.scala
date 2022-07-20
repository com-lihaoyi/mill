package test

object Compat {
  def mapToSet(l: List[Int], f: Int => Int): Set[Int] =
    l.map(f)(collection.breakOut)
}
