package forge

import java.io.FileOutputStream
import java.nio.{file => jnio}
import java.util.jar.JarEntry

import sourcecode.Enclosing

import scala.collection.JavaConverters._
import scala.collection.mutable

class MultiBiMap[K, V](){
  private[this] val valueToKey = mutable.Map.empty[V, K]
  private[this] val keyToValues = mutable.Map.empty[K, List[V]]
  def containsValue(v: V) = valueToKey.contains(v)
  def lookupValue(v: V) = valueToKey(v)
  def lookupValueOpt(v: V) = valueToKey.get(v)
  def add(k: K, v: V): Unit = {
    valueToKey(v) = k
    keyToValues(k) = v :: keyToValues.getOrElse(k, Nil)
  }
  def removeAll(k: K): Seq[V] = keyToValues.get(k) match {
    case None => Nil
    case Some(vs) =>
      vs.foreach(valueToKey.remove)

      keyToValues.remove(k)
      vs
  }
  def addAll(k: K, vs: Seq[V]): Unit = {
    vs.foreach(valueToKey.update(_, k))
    keyToValues(k) = vs ++: keyToValues.getOrElse(k, Nil)
  }
}

/**
  * A collection with enforced uniqueness, fast contains and deterministic
  * ordering. When a duplicate happens, it can be configured to either remove
  * it automatically or to throw an exception and fail loudly
  */
trait OSet[V] extends TraversableOnce[V]{
  def contains(v: V): Boolean
  def items: IndexedSeq[V]
  def flatMap[T](f: V => TraversableOnce[T]): OSet[T]
  def map[T](f: V => T): OSet[T]
  def filter(f: V => Boolean): OSet[V]


}
object OSet{
  def apply[V](items: V*) = from(items)
  def dedup[V](items: V*) = from(items, dedup = true)

  def from[V](items: TraversableOnce[V], dedup: Boolean = false): OSet[V] = {
    val set = new MutableOSet[V](dedup)
    items.foreach(set.append)
    set
  }
}
class MutableOSet[V](dedup: Boolean = false) extends OSet[V]{
  private[this] val items0 = mutable.ArrayBuffer.empty[V]
  private[this] val set0 = mutable.Set.empty[V]
  def contains(v: V) = set0.contains(v)
  def append(v: V) = if (!contains(v)){
    set0.add(v)
    items0.append(v)
  }else if (!dedup) {
    throw new Exception("Duplicated item inserted into OrderedSet: " + v)
  }
  def appendAll(vs: Seq[V]) = vs.foreach(append)
  def items: IndexedSeq[V] = items0
  def set: collection.Set[V] = set0

  def map[T](f: V => T): OSet[T] = {
    val output = new MutableOSet[T]
    for(i <- items) output.append(f(i))
    output
  }
  def flatMap[T](f: V => TraversableOnce[T]): OSet[T] = {
    val output = new MutableOSet[T]
    for(i <- items) for(i0 <- f(i)) output.append(i0)
    output
  }
  def filter(f: V => Boolean): OSet[V] = {
    val output = new MutableOSet[V]
    for(i <- items) if (f(i)) output.append(i)
    output
  }

  // Members declared in scala.collection.GenTraversableOnce
  def isTraversableAgain: Boolean = items.isTraversableAgain
  def toIterator: Iterator[V] = items.toIterator
  def toStream: Stream[V] = items.toStream

  // Members declared in scala.collection.TraversableOnce
  def copyToArray[B >: V](xs: Array[B],start: Int,len: Int): Unit = items.copyToArray(xs, start, len)
  def exists(p: V => Boolean): Boolean = items.exists(p)
  def find(p: V => Boolean): Option[V] = items.find(p)
  def forall(p: V => Boolean): Boolean = items.forall(p)
  def foreach[U](f: V => U): Unit = items.foreach(f)
  def hasDefiniteSize: Boolean = items.hasDefiniteSize
  def isEmpty: Boolean = items.isEmpty
  def seq: scala.collection.TraversableOnce[V] = items
  def toTraversable: Traversable[V] = items

  override def hashCode() = items.hashCode()
  override def equals(other: Any) = other match{
    case s: OSet[_] => items.equals(s.items)
    case _ => super.equals(other)
  }
  override def toString = items.mkString("OSet(", ", ", ")")
}