package hello;


import scala.collection.AbstractIterator

object Hello{

  class Elements[T](arr: Array[T]) extends AbstractIterator[T] {
    val end = arr.length
    var index = 0

    def hasNext: Boolean = index < end

    def next(): T = {
      val x = arr(index)
      index += 1
      x
    }
  }

  def manualIterator(n: Int): Int = {
    val iter = new Elements(Array(0, 1, 2, 3))
    iter.map(_ + 1).next()
  }

  def manualIterator2(n: Int): Int = {
    val box = Array(0)
    val iter = new Elements(Array(0, 1, 2, 3))
    iter.map(_ + n).foreach(x => box(0) += x)
    box(0)
  }
}
