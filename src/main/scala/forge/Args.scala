package forge

import java.nio.{file => jnio}

class Args(val args: IndexedSeq[_], val dest: jnio.Path){
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
