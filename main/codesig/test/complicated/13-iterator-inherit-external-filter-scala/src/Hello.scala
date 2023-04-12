package hello

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
/* EXPECTED TRANSITIVE
{
    "hello.Hello$#manualIterator3(I)I": [
        "hello.Hello$BigTestIterator#hasNext()Z",
        "hello.Hello$BigTestIterator#next()java.lang.Object",
        "hello.Hello$FilterBigTestIterator#hasNext()Z",
        "hello.Hello$FilterBigTestIterator#next()java.lang.Object",
        "hello.Hello$SingletonBigTestIterator#<init>(java.lang.Object)V",
        "hello.Hello$SingletonBigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator",
        "hello.Hello$SingletonBigTestIterator#hasNext()Z",
        "hello.Hello$SingletonBigTestIterator#next()java.lang.Object"
    ],
    "hello.Hello$BigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator": [
        "hello.Hello$FilterBigTestIterator#<init>(hello.Hello$BigTestIteratorscala.Function1)V"
    ],
    "hello.Hello$BigTestIterator.filter$(hello.Hello$BigTestIteratorscala.Function1)hello.Hello$BigTestIterator": [
        "hello.Hello$BigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator"
    ],
    "hello.Hello$FilterBigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator": [
        "hello.Hello$BigTestIterator.filter$(hello.Hello$BigTestIteratorscala.Function1)hello.Hello$BigTestIterator"
    ],
    "hello.Hello$FilterBigTestIterator#hasNext()Z": [
        "hello.Hello$BigTestIterator#hasNext()Z",
        "hello.Hello$BigTestIterator#next()java.lang.Object",
        "hello.Hello$FilterBigTestIterator#hasNext()Z",
        "hello.Hello$FilterBigTestIterator#next()java.lang.Object",
        "hello.Hello$SingletonBigTestIterator#hasNext()Z",
        "hello.Hello$SingletonBigTestIterator#next()java.lang.Object"
    ],
    "hello.Hello$SingletonBigTestIterator#filter(scala.Function1)hello.Hello$BigTestIterator": [
        "hello.Hello$BigTestIterator.filter$(hello.Hello$BigTestIteratorscala.Function1)hello.Hello$BigTestIterator"
    ],
    "hello.Hello$SingletonBigTestIterator#hasNext()Z": [
        "hello.Hello$SingletonBigTestIterator#ready()Z"
    ],
    "hello.Hello$SingletonBigTestIterator#next()java.lang.Object": [
        "hello.Hello$SingletonBigTestIterator#ready_$eq(Z)V"
    ],
    "hello.Hello.manualIterator3(I)I": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#manualIterator3(I)I"
    ]
}
*/
