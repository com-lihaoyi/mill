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
