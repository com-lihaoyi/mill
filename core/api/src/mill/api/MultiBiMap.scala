package mill.api

import scala.collection.mutable

/**
 * A map from keys to collections of values: you can assign multiple values
 * to any particular key. Also allows lookups in both directions: what values
 * are assigned to a key or what key a value is assigned to.
 */
private[mill] trait MultiBiMap[K, V] {
  def containsValue(v: V): Boolean
  def lookupKey(k: K): collection.Seq[V]
  def lookupValue(v: V): K
  def lookupValueOpt(v: V): Option[K]
  def add(k: K, v: V): Unit
  def removeAll(k: K): collection.Seq[V]
  def addAll(k: K, vs: IterableOnce[V]): Unit
  def keys(): Iterator[K]
  def items(): Iterator[(K, collection.Seq[V])]
  def values(): Iterator[collection.Seq[V]]
  def keyCount: Int
  def foreach(f: (K, collection.Seq[V]) => Unit): Unit
}

private[mill] object MultiBiMap {

  class Mutable[K, V]() extends MultiBiMap[K, V] {
    private val valueToKey = mutable.LinkedHashMap.empty[V, K]
    private val keyToValues = mutable.LinkedHashMap.empty[K, mutable.Buffer[V]]
    def containsValue(v: V): Boolean = valueToKey.contains(v)
    def lookupKey(k: K): collection.Seq[V] = keyToValues(k)
    def lookupKeyOpt(k: K): Option[collection.Seq[V]] = keyToValues.get(k)
    def lookupValue(v: V): K = valueToKey(v)
    def lookupValueOpt(v: V): Option[K] = valueToKey.get(v)
    def add(k: K, v: V): Unit = {
      valueToKey(v) = k
      keyToValues.getOrElseUpdate(k, mutable.Buffer.empty[V]).append(v)
    }
    def removeAll(k: K): collection.Seq[V] = keyToValues.get(k) match {
      case None => Seq()
      case Some(vs) =>
        vs.iterator.foreach(valueToKey.remove)
        keyToValues.remove(k)
        vs
    }
    def addAll(k: K, vs: IterableOnce[V]): Unit = vs.iterator.foreach(this.add(k, _))

    def keys(): Iterator[K] = keyToValues.keysIterator

    def values(): Iterator[collection.Seq[V]] = keyToValues.valuesIterator

    def items(): Iterator[(K, collection.Seq[V])] = keyToValues.iterator

    def keyCount: Int = keyToValues.size

    def foreach(f: (K, collection.Seq[V]) => Unit): Unit = keyToValues.foreach(f(_, _))
  }
}
