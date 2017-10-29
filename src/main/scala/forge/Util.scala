package forge


import ammonite.ops.ls
import play.api.libs.json.{Format, Json}

import scala.collection.mutable

object PathRef{
  implicit def jsonFormatter: Format[PathRef] = Json.format
}
case class PathRef(path: ammonite.ops.Path){
  override def hashCode() = {
    if (!path.isDir) path.hashCode() + path.mtime.toMillis.toInt
    else ls.rec.iter(path)
          .filter(_.isFile)
          .map(x => x.toString.hashCode + x.mtime.toMillis)
          .sum
          .toInt
  }
}


trait MultiBiMap[K, V]{
  def containsValue(v: V): Boolean
  def lookupKey(k: K): OSet[V]
  def lookupValue(v: V): K
  def lookupValueOpt(v: V): Option[K]
  def add(k: K, v: V): Unit
  def removeAll(k: K): OSet[V]
  def addAll(k: K, vs: TraversableOnce[V]): Unit
  def keys(): Iterator[K]
  def values(): Iterator[OSet[V]]
}
class MutableMultiBiMap[K, V]() extends MultiBiMap[K, V]{
  private[this] val valueToKey = mutable.LinkedHashMap.empty[V, K]
  private[this] val keyToValues = mutable.LinkedHashMap.empty[K, MutableOSet[V]]
  def containsValue(v: V) = valueToKey.contains(v)
  def lookupKey(k: K) = keyToValues(k)
  def lookupValue(v: V) = valueToKey(v)
  def lookupValueOpt(v: V) = valueToKey.get(v)
  def add(k: K, v: V): Unit = {
    valueToKey(v) = k
    keyToValues.getOrElseUpdate(k, new MutableOSet[V]()).append(v)
  }
  def removeAll(k: K): OSet[V] = keyToValues.get(k) match {
    case None => OSet()
    case Some(vs) =>
      vs.foreach(valueToKey.remove)

      keyToValues.remove(k)
      vs
  }
  def addAll(k: K, vs: TraversableOnce[V]): Unit = vs.foreach(this.add(k, _))

  def keys() = keyToValues.keysIterator

  def values() = keyToValues.valuesIterator
}

/**
  * A collection with enforced uniqueness, fast contains and deterministic
  * ordering. Raises an exception if a duplicate is found; call
  * `toSeq.distinct` if you explicitly want to make it swallow duplicates
  */
trait OSet[V] extends TraversableOnce[V]{
  def contains(v: V): Boolean
  def items: IndexedSeq[V]
  def flatMap[T](f: V => TraversableOnce[T]): OSet[T]
  def map[T](f: V => T): OSet[T]
  def filter(f: V => Boolean): OSet[V]
  def collect[T](f: PartialFunction[V, T]): OSet[T]
  def zipWithIndex: OSet[(V, Int)]
  def reverse: OSet[V]
}

object OSet{
  def apply[V](items: V*) = from(items)

  def from[V](items: TraversableOnce[V]): OSet[V] = {
    val set = new MutableOSet[V]()
    items.foreach(set.append)
    set
  }
}

class MutableOSet[V]() extends OSet[V]{

  private[this] val set0 = mutable.LinkedHashSet.empty[V]
  def contains(v: V) = set0.contains(v)
  def append(v: V) = if (!contains(v)){
    set0.add(v)

  }else {
    throw new Exception("Duplicated item inserted into OrderedSet: " + v)
  }
  def appendAll(vs: Seq[V]) = vs.foreach(append)
  def items: IndexedSeq[V] = set0.toIndexedSeq
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

  def collect[T](f: PartialFunction[V, T]) = this.filter(f.isDefinedAt).map(x => f(x))

  def zipWithIndex = {
    var i = 0
    this.map{ x =>
      i += 1
      (x, i-1)
    }
  }

  def reverse = OSet.from(items.reverseIterator)

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