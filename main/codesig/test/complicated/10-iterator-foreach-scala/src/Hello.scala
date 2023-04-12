package hello;

import scala.collection.AbstractIterator

trait TestIter[+A]{
  def hasNext: Boolean
  def next(): A
  def foreach[U](f: A => U) = { while (hasNext) f(next()) }
}

class SubTestIter() extends TestIter[Nothing]{
  def hasNext: Boolean = false
  def next(): Nothing = throw new NoSuchElementException("next on empty iterator")
}

object Hello{
  def minimizedIterator(): Array[Int] = {
    val iterator = new SubTestIter()
    val holder = Array(1)
    iterator.foreach(x => holder(0) = x)
    holder
  }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello$#minimizedIterator()[I": [
        "hello.SubTestIter#<init>()V",
        "hello.SubTestIter#foreach(scala.Function1)V"
    ],
    "hello.Hello.minimizedIterator()[I": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#minimizedIterator()[I"
    ],
    "hello.SubTestIter#foreach(scala.Function1)V": [
        "hello.TestIter.foreach$(hello.TestIterscala.Function1)V"
    ],
    "hello.SubTestIter#next()java.lang.Object": [
        "hello.SubTestIter#next()scala.runtime.Nothing$"
    ],
    "hello.TestIter#foreach(scala.Function1)V": [
        "hello.Hello$#<init>()V",
        "hello.SubTestIter#<init>()V",
        "hello.SubTestIter#hasNext()Z",
        "hello.SubTestIter#next()java.lang.Object",
        "hello.TestIter#hasNext()Z",
        "hello.TestIter#next()java.lang.Object"
    ],
    "hello.TestIter.foreach$(hello.TestIterscala.Function1)V": [
        "hello.TestIter#foreach(scala.Function1)V"
    ]
}
*/
