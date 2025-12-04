package hello

trait TestIter[+A] {
  def hasNext: Boolean
  def next(): A
  def foreach[U](f: A => U) = { while (hasNext) f(next()) }
}

class SubTestIter() extends TestIter[Nothing] {
  def hasNext: Boolean = false
  def next(): Nothing = throw new NoSuchElementException("next on empty iterator")
}

object Hello {
  def minimizedIterator(): Array[Int] = {
    val iterator = SubTestIter()
    val holder = Array(1)
    iterator.foreach(x => holder(0) = x)
    holder
  }
}

/* expected-direct-call-graph
{
    "hello.Hello$#minimizedIterator()int[]": [
        "hello.SubTestIter#<init>()void",
        "hello.SubTestIter#foreach(scala.Function1)void"
    ],
    "hello.Hello.minimizedIterator()int[]": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#minimizedIterator()int[]"
    ],
    "hello.SubTestIter#foreach(scala.Function1)void": [
        "hello.TestIter.foreach$(hello.TestIter,scala.Function1)void"
    ],
    "hello.SubTestIter#next()java.lang.Object": [
        "hello.SubTestIter#next()scala.runtime.Nothing$"
    ],
    "hello.TestIter#foreach(scala.Function1)void": [
        "hello.SubTestIter#hasNext()boolean",
        "hello.SubTestIter#next()java.lang.Object",
        "hello.TestIter#hasNext()boolean",
        "hello.TestIter#next()java.lang.Object"
    ],
    "hello.TestIter.foreach$(hello.TestIter,scala.Function1)void": [
        "hello.TestIter#foreach(scala.Function1)void"
    ]
}
 */
