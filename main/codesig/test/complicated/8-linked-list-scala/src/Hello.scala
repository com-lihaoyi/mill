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

/* EXPECTED DEPENDENCIES
{
    "hello.Hello$#simpleLinkedListForeach()int[]": [
        "hello.Hello$TestCons#<init>(java.lang.Object,hello.Hello$TestList)void",
        "hello.Hello$TestList#foreach(scala.Function1)void",
        "hello.Hello$TestNil$#<init>()void"
    ],
    "hello.Hello$TestCons#<init>(java.lang.Object,hello.Hello$TestList)void": [
        "hello.Hello$TestList#<init>()void"
    ],
    "hello.Hello$TestCons#tail()hello.Hello$TestList": [
        "hello.Hello$TestCons#tl()hello.Hello$TestList"
    ],
    "hello.Hello$TestList#foreach(scala.Function1)void": [
        "hello.Hello$TestCons#head()java.lang.Object",
        "hello.Hello$TestCons#isEmpty()boolean",
        "hello.Hello$TestCons#tail()hello.Hello$TestList",
        "hello.Hello$TestList#head()java.lang.Object",
        "hello.Hello$TestList#isEmpty()boolean",
        "hello.Hello$TestList#tail()hello.Hello$TestList",
        "hello.Hello$TestNil$#head()java.lang.Object",
        "hello.Hello$TestNil$#isEmpty()boolean",
        "hello.Hello$TestNil$#tail()hello.Hello$TestList"
    ],
    "hello.Hello$TestNil$#<init>()void": [
        "hello.Hello$TestList#<init>()void"
    ],
    "hello.Hello$TestNil$#head()java.lang.Object": [
        "hello.Hello$TestNil$#head()scala.runtime.Nothing$"
    ],
    "hello.Hello$TestNil$#tail()hello.Hello$TestList": [
        "hello.Hello$TestNil$#tail()scala.runtime.Nothing$"
    ],
    "hello.Hello.simpleLinkedListForeach()int[]": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#simpleLinkedListForeach()int[]"
    ]
}
*/
