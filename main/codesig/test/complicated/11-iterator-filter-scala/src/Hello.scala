package hello;


import scala.collection.AbstractIterator

object Hello{
  class SingletonBigTestIterator[T](a: T) extends BigTestIterator[T] {
    var ready = true
    def hasNext: Boolean = ready
    def next(): T = {
      ready = false
      a
    }
  }

  trait BigTestIterator[+A] {
    def hasNext: Boolean

    def next(): A

    def filter(p: A => Boolean): BigTestIterator[A] = new FilterBigTestIterator[A](this, p)
  }

  class FilterBigTestIterator[A](parent: BigTestIterator[A], pred: A => Boolean) extends BigTestIterator [A]{
    private[this] var hd: A = _
    private[this] var hdDefined: Boolean = false

    def hasNext: Boolean = hdDefined || {
      do {
        if (!parent.hasNext) return false
        hd = parent.next()
      } while (!pred(hd))
      hdDefined = true
      true
    }

    def next() = {
      hdDefined = false; hd
    }
  }

  def manualIterator3(n: Int): Int = {
    val iter = new SingletonBigTestIterator(n).filter(_ % 2 == 0)
    if (iter.hasNext) iter.next()
    else n
  }
}
