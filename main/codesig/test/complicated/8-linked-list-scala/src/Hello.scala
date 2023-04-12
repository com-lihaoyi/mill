package hello;


import scala.collection.AbstractIterator

object Hello{

  sealed abstract class TestList[+A] {
    def isEmpty: Boolean
    def head: A
    def tail: TestList[A]

    def foreach[U](f: A => U) {
      var these = this
      while (!these.isEmpty) {
        f(these.head)
        these = these.tail
      }
    }
  }

  object TestNil extends TestList[Nothing] {
    def isEmpty = true
    def head = throw new Exception()
    def tail = throw new Exception()
  }

  class TestCons[B](val head: B, val tl: TestList[B]) extends TestList[B] {
    def tail = tl
    def isEmpty = false
  }

  def simpleLinkedListForeach(): Array[Int] = {
    val holder = Array(1)

    new TestCons(1, new TestCons(2, new TestCons(3, TestNil))).foreach(x => holder(0) += x)
    new TestCons(1L, new TestCons(2L, new TestCons(3L, TestNil))).foreach(x => holder(0) += x.toInt)
    holder
  }
}

/* EXPECTED TRANSITIVE
{}
*/
