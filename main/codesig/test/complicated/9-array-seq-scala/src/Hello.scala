package hello

import scala.collection.AbstractIterator

object Hello{
  class TestArraySeq[T](inner: Array[T]){
    def foreach[V](f: T => V) = {
      var i = 0
      while (i < inner.length){
        f(inner(i))
        i += 1
      }
    }
  }

  def simpleArraySeqForeach(): Array[Int] = {
    val holder = Array(1)
    val arr = new TestArraySeq[String](Array("a", "bb", "CCC"))
    arr.foreach(x => holder(0) += x.length)

    holder
  }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello$#simpleArraySeqForeach()[I": [
        "hello.Hello$TestArraySeq#<init>(java.lang.Object)V",
        "hello.Hello$TestArraySeq#foreach(scala.Function1)V"
    ],
    "hello.Hello.simpleArraySeqForeach()[I": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#simpleArraySeqForeach()[I"
    ]
}
*/
