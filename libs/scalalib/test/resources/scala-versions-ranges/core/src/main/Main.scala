package main

object Main {
  def main(args: Array[String]): Unit = {
    assert(
      Compat.mapToSet(List(1, 2, 3, 4), _ + 1) == Set(2, 3, 4, 5)
    )
  }
}
