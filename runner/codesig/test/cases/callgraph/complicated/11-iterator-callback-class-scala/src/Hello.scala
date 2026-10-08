package hello

object Hello {
  class TestElements() extends TestIterator

  trait TestIterator {
    def run(f: TestCallback[Int, Int]): Int = f(123)
  }
  abstract class TestCallback[T, void] {
    def apply(x: T): void
  }
  class TestCallbackImpl extends TestCallback[Int, Int] {
    // Because `TestCallbackImpl` is a SAM implementation,
    // we treat `apply` as being called from `<init>`
    def apply(x: Int): Int = x + 1
  }

  def staticSpecialInterfaceMethods(): Int = {
    TestElements().run(TestCallbackImpl())
  }
}

/* expected-direct-call-graph
{
    "hello.Hello$#staticSpecialInterfaceMethods()int": [
        "hello.Hello$TestCallbackImpl#<init>()void",
        "hello.Hello$TestElements#<init>()void",
        "hello.Hello$TestElements#run(hello.Hello$TestCallback)int"
    ],
    "hello.Hello$TestCallback#<init>()void": [
        "hello.Hello$TestCallback#apply(java.lang.Object)java.lang.Object"
    ],
    "hello.Hello$TestCallbackImpl#<init>()void": [
        "hello.Hello$TestCallback#<init>()void",
        "hello.Hello$TestCallbackImpl#apply(java.lang.Object)java.lang.Object"
    ],
    "hello.Hello$TestCallbackImpl#apply(java.lang.Object)java.lang.Object": [
        "hello.Hello$TestCallbackImpl#apply(int)int"
    ],
    "hello.Hello$TestElements#run(hello.Hello$TestCallback)int": [
        "hello.Hello$TestIterator.run$(hello.Hello$TestIterator,hello.Hello$TestCallback)int"
    ],
    "hello.Hello$TestIterator.run$(hello.Hello$TestIterator,hello.Hello$TestCallback)int": [
        "hello.Hello$TestIterator#run(hello.Hello$TestCallback)int"
    ],
    "hello.Hello.staticSpecialInterfaceMethods()int": [
        "hello.Hello$#<init>()void",
        "hello.Hello$#staticSpecialInterfaceMethods()int"
    ]
}
 */
