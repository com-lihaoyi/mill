package hello

import scala.collection.AbstractIterator

object Hello {
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

    def filter(p: A => Boolean): BigTestIterator[A] = FilterBigTestIterator[A](this, p)
  }

  class FilterBigTestIterator[A](parent: BigTestIterator[A], pred: A => Boolean)
      extends BigTestIterator[A] {
    private var hd: A = compiletime.uninitialized
    private var hdDefined: Boolean = false

    def hasNext: Boolean = hdDefined || {
      while ({
        if (!parent.hasNext) return false
        hd = parent.next()
        !pred(hd)
      }) {}
      hdDefined = true
      true
    }

    def next() = {
      hdDefined = false; hd
    }
  }

  def manualIterator3(n: Int): Int = {
    val iter = SingletonBigTestIterator(n).filter(_ % 2 == 0)
    if (iter.hasNext) iter.next()
    else n
  }
}
/* expected-direct-call-graph
{
    "hello.Hello$#manualIterator3(int)int": [
        "hello.Hello$BigTestIterator#hasNext()boolean",
        "hello.Hello$BigTestIterator#next()java.lang.Object",
        "hello.Hello$FilterBigTestIterator#hasNext()boolean",
        "hello.Hello$FilterBigTestIterator#next()java.lang.Object",
        "hello.Hello$SingletonBigTestIterator#<init>(java.lang.Object)void",
        "hello.Hello$SingletonBigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator",
        "hello.Hello$SingletonBigTestIterator#hasNext()boolean",
        "hello.Hello$SingletonBigTestIterator#next()java.lang.Object"
    ],
    "hello.Hello$BigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator": [
        "hello.Hello$FilterBigTestIterator#<init>(hello.Hello$BigTestIterator,scala.Function1)void"
    ],
    "hello.Hello$BigTestIterator.filter$(hello.Hello$BigTestIterator,scala.Function1)hello.Hello$BigTestIterator": [
        "hello.Hello$BigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator"
    ],
    "hello.Hello$FilterBigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator": [
        "hello.Hello$BigTestIterator.filter$(hello.Hello$BigTestIterator,scala.Function1)hello.Hello$BigTestIterator"
    ],
    "hello.Hello$FilterBigTestIterator#hasNext()boolean": [
        "hello.Hello$BigTestIterator#hasNext()boolean",
        "hello.Hello$BigTestIterator#next()java.lang.Object",
        "hello.Hello$FilterBigTestIterator#hasNext()boolean",
        "hello.Hello$FilterBigTestIterator#next()java.lang.Object",
        "hello.Hello$SingletonBigTestIterator#hasNext()boolean",
        "hello.Hello$SingletonBigTestIterator#next()java.lang.Object"
    ],
    "hello.Hello$SingletonBigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator": [
        "hello.Hello$BigTestIterator.filter$(hello.Hello$BigTestIterator,scala.Function1)hello.Hello$BigTestIterator"
    ],
    "hello.Hello$SingletonBigTestIterator#hasNext()boolean": [
        "hello.Hello$SingletonBigTestIterator#ready()boolean"
    ],
    "hello.Hello$SingletonBigTestIterator#next()java.lang.Object": [
        "hello.Hello$SingletonBigTestIterator#ready_$eq(boolean)void"
    ],
    "hello.Hello.manualIterator3(int)int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#manualIterator3(int)int"
    ]
}
 */
