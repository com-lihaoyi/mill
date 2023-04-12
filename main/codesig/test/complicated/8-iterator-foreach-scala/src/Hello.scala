package hello;


import scala.collection.AbstractIterator

object Hello{
  trait TestIter[+A]{
    def hasNext: Boolean
    def next(): A
    def foreach[U](f: A => U) = { while (hasNext) f(next()) }
  }
  class SubTestIter() extends TestIter[Nothing]{
    def hasNext: Boolean = false
    def next(): Nothing = throw new NoSuchElementException("next on empty iterator")
  }

  def minimizedIterator(): Array[Int] = {
    val iterator = new SubTestIter()
    val holder = Array(1)
    iterator.foreach(x => holder(0) = x)
    holder
  }

}

/* EXPECTED TRANSITIVE
{}
*/
