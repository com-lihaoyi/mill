package hello

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
{
    "hello.Hello$#simpleLinkedListForeach()[I": [
        "hello.Hello$TestCons#<init>(java.lang.Objecthello.Hello$TestList)V",
        "hello.Hello$TestList#foreach(scala.Function1)V",
        "hello.Hello$TestNil$#<init>()V"
    ],
    "hello.Hello$TestCons#<init>(java.lang.Objecthello.Hello$TestList)V": [
        "hello.Hello$TestList#<init>()V"
    ],
    "hello.Hello$TestCons#tail()hello.Hello$TestList": [
        "hello.Hello$TestCons#tl()hello.Hello$TestList"
    ],
    "hello.Hello$TestList#foreach(scala.Function1)V": [
        "hello.Hello$TestCons#head()java.lang.Object",
        "hello.Hello$TestCons#isEmpty()Z",
        "hello.Hello$TestCons#tail()hello.Hello$TestList",
        "hello.Hello$TestList#head()java.lang.Object",
        "hello.Hello$TestList#isEmpty()Z",
        "hello.Hello$TestList#tail()hello.Hello$TestList",
        "hello.Hello$TestNil$#head()java.lang.Object",
        "hello.Hello$TestNil$#isEmpty()Z",
        "hello.Hello$TestNil$#tail()hello.Hello$TestList"
    ],
    "hello.Hello$TestNil$#<init>()V": [
        "hello.Hello$TestList#<init>()V"
    ],
    "hello.Hello$TestNil$#head()java.lang.Object": [
        "hello.Hello$TestNil$#head()scala.runtime.Nothing$"
    ],
    "hello.Hello$TestNil$#tail()hello.Hello$TestList": [
        "hello.Hello$TestNil$#tail()scala.runtime.Nothing$"
    ],
    "hello.Hello.simpleLinkedListForeach()[I": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#simpleLinkedListForeach()[I"
    ]
}
*/
