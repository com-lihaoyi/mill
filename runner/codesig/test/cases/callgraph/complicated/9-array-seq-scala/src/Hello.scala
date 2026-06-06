package hello

import scala.collection.AbstractIterator

object Hello {
  class TestArraySeq[T](inner: Array[T]) {
    def foreach[void](f: T => void) = {
      var i = 0
      while (i < inner.length) {
        f(inner(i))
        i += 1
      }
    }
  }

  def simpleArraySeqForeach(): Array[Int] = {
    val holder = Array(1)
    val arr = TestArraySeq[String](Array("a", "bb", "CCC"))
    arr.foreach(x => holder(0) += x.length)

    holder
  }
}

/* expected-direct-call-graph
{
    "hello.Hello$#simpleArraySeqForeach()int[]": [
        "hello.Hello$TestArraySeq#<init>(java.lang.Object)void",
        "hello.Hello$TestArraySeq#foreach(scala.Function1)void"
    ],
    "hello.Hello.simpleArraySeqForeach()int[]": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#simpleArraySeqForeach()int[]"
    ]
}
 */
