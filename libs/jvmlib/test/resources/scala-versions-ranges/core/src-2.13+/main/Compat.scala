package main

object Compat {
  def mapToSet(l: List[Int], f: Int => Int): Set[Int] = l.view.map(f).toSet
}
