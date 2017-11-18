package mill.util



import scala.collection.mutable

/**
  * A collection with enforced uniqueness, fast contains and deterministic
  * ordering. Raises an exception if a duplicate is found; call
  * `toSeq.distinct` if you explicitly want to make it swallow duplicates
  */
trait OSet[V] extends TraversableOnce[V]{
  def contains(v: V): Boolean
  def items: Iterator[V]
  def indexed: IndexedSeq[V]
  def flatMap[T](f: V => TraversableOnce[T]): OSet[T]
  def map[T](f: V => T): OSet[T]
  def filter(f: V => Boolean): OSet[V]
  def collect[T](f: PartialFunction[V, T]): OSet[T]
  def zipWithIndex: OSet[(V, Int)]
  def reverse: OSet[V]
}

object OSet{
  implicit def jsonFormat[T: upickle.default.ReadWriter]: upickle.default.ReadWriter[OSet[T]] =
    upickle.default.ReadWriter[OSet[T]] (
      oset => upickle.default.writeJs(oset.toList),
      {case json => OSet.from(upickle.default.readJs[Seq[T]](json))}
    )
  def apply[V](items: V*) = from(items)

  def from[V](items: TraversableOnce[V]): OSet[V] = {
    val set = new OSet.Mutable[V]()
    items.foreach(set.append)
    set
  }


  class Mutable[V]() extends OSet[V]{

    private[this] val set0 = mutable.LinkedHashSet.empty[V]
    def contains(v: V) = set0.contains(v)
    def append(v: V) = if (!contains(v)){
      set0.add(v)

    }else {
      throw new Exception("Duplicated item inserted into OrderedSet: " + v)
    }
    def appendAll(vs: Seq[V]) = vs.foreach(append)
    def items = set0.iterator
    def indexed: IndexedSeq[V] = items.toIndexedSeq
    def set: collection.Set[V] = set0

    def map[T](f: V => T): OSet[T] = {
      val output = new OSet.Mutable[T]
      for(i <- items) output.append(f(i))
      output
    }
    def flatMap[T](f: V => TraversableOnce[T]): OSet[T] = {
      val output = new OSet.Mutable[T]
      for(i <- items) for(i0 <- f(i)) output.append(i0)
      output
    }
    def filter(f: V => Boolean): OSet[V] = {
      val output = new OSet.Mutable[V]
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

    def reverse = OSet.from(indexed.reverseIterator)

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
    def toTraversable: Traversable[V] = items.toTraversable

    override def hashCode() = items.hashCode()
    override def equals(other: Any) = other match{
      case s: OSet[_] => items.sameElements(s.items)
      case _ => super.equals(other)
    }
    override def toString = items.mkString("OSet(", ", ", ")")
  }
}
