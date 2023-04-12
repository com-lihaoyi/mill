package hello;

import scala.collection.AbstractIterator

object Hello{
  class TestElements() extends TestIterator

  trait TestIterator {
    def run(f: TestCallback[Int, Int]): Int = f(123)
  }
  abstract class TestCallback[T, V]{
    def apply(x: T): V
  }
  class TestCallbackImpl extends TestCallback[Int, Int]{
    def apply(x: Int): Int = x + 1
  }

  def staticSpecialInterfaceMethods(): Int = {
    new TestElements().run(new TestCallbackImpl())
  }
}

/* EXPECTED TRANSITIVE
{
    "hello.Hello$#staticSpecialInterfaceMethods()I": [
        "hello.Hello$TestCallbackImpl#<init>()V",
        "hello.Hello$TestElements#<init>()V",
        "hello.Hello$TestElements#run(hello.Hello$TestCallback)I"
    ],
    "hello.Hello$TestCallbackImpl#<init>()V": [
        "hello.Hello$TestCallback#<init>()V"
    ],
    "hello.Hello$TestCallbackImpl#apply(java.lang.Object)java.lang.Object": [
        "hello.Hello$TestCallbackImpl#apply(I)I"
    ],
    "hello.Hello$TestElements#run(hello.Hello$TestCallback)I": [
        "hello.Hello$TestIterator.run$(hello.Hello$TestIteratorhello.Hello$TestCallback)I"
    ],
    "hello.Hello$TestIterator#run(hello.Hello$TestCallback)I": [
        "hello.Hello$TestCallback#apply(java.lang.Object)java.lang.Object",
        "hello.Hello$TestCallbackImpl#apply(java.lang.Object)java.lang.Object"
    ],
    "hello.Hello$TestIterator.run$(hello.Hello$TestIteratorhello.Hello$TestCallback)I": [
        "hello.Hello$TestIterator#run(hello.Hello$TestCallback)I"
    ],
    "hello.Hello.staticSpecialInterfaceMethods()I": [
        "hello.Hello$#<init>()V",
        "hello.Hello$#staticSpecialInterfaceMethods()I"
    ]
}
*/
