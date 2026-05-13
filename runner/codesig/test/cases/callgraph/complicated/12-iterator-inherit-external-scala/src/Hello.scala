package hello

import scala.collection.AbstractIterator

object Hello {

  class Elements[T](arr: Array[T]) extends AbstractIterator[T] {
    val end = arr.length
    var index = 0

    def hasNext: Boolean = index < end

    def next(): T = {
      val x = arr(index)
      index += 1
      x
    }
  }

  def manualIterator(n: Int): Int = {
    val iter = Elements(Array(0, 1, 2, 3))
    iter.map(_ + getInt()).next()
  }

  def getInt() = 1

  def manualIterator2(n: Int): Int = {
    val box = Array(0)
    val iter = Elements(Array(0, 1, 2, 3))
    iter.map(_ + n).foreach(x => box(0) += x + getInt())
    box(0)
  }
}

// `.map` is an external method call, and thus we have to assume it can call
// any method on the type it is defined on. That is why `manualIterator` and
// `manualIterator2` have the potential to call `#hasNext`, and
// `manualIterator2` can call `#next`, even though we never perform those calls
// in our code

/* expected-direct-call-graph
{
    "hello.Hello$#manualIterator(int)int": [
        "hello.Hello$#getInt()int",
        "hello.Hello$Elements#<init>(java.lang.Object)void",
        "hello.Hello$Elements#hasNext()boolean",
        "hello.Hello$Elements#next()java.lang.Object"
    ],
    "hello.Hello$#manualIterator2(int)int": [
        "hello.Hello$#getInt()int",
        "hello.Hello$Elements#<init>(java.lang.Object)void",
        "hello.Hello$Elements#hasNext()boolean",
        "hello.Hello$Elements#next()java.lang.Object"
    ],
    "hello.Hello$Elements#<init>(java.lang.Object)void": [
        "hello.Hello$Elements#hasNext()boolean",
        "hello.Hello$Elements#next()java.lang.Object"
    ],
    "hello.Hello$Elements#hasNext()boolean": [
        "hello.Hello$Elements#end()int",
        "hello.Hello$Elements#index()int"
    ],
    "hello.Hello$Elements#next()java.lang.Object": [
        "hello.Hello$Elements#index()int",
        "hello.Hello$Elements#index_$eq(int)void"
    ],
    "hello.Hello.getInt()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#getInt()int"
    ],
    "hello.Hello.manualIterator(int)int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#manualIterator(int)int"
    ],
    "hello.Hello.manualIterator2(int)int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#manualIterator2(int)int"
    ]
}
 */
