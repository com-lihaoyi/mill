package mill.util



import scala.collection.mutable
object Strict extends AggWrapper(true)
object Loose extends AggWrapper(false)
sealed class AggWrapper(strictUniqueness: Boolean){
  /**
    * A collection with enforced uniqueness, fast contains and deterministic
    * ordering. Raises an exception if a duplicate is found; call
    * `toSeq.distinct` if you explicitly want to make it swallow duplicates
    */
  trait Agg[V] extends TraversableOnce[V]{
    def contains(v: V): Boolean
    def items: Iterator[V]
    def indexed: IndexedSeq[V]
    def flatMap[T](f: V => TraversableOnce[T]): Agg[T]
    def map[T](f: V => T): Agg[T]
    def filter(f: V => Boolean): Agg[V]
    def withFilter(f: V => Boolean): Agg[V]
    def collect[T](f: PartialFunction[V, T]): Agg[T]
    def zipWithIndex: Agg[(V, Int)]
    def reverse: Agg[V]
    def zip[T](other: Agg[T]): Agg[(V, T)]
    def ++[T >: V](other: TraversableOnce[T]): Agg[T]
    def length: Int
  }

  object Agg{
    def empty[V]: Agg[V] = new Agg.Mutable[V]
    implicit def jsonFormat[T: upickle.default.ReadWriter]: upickle.default.ReadWriter[Agg[T]] =
      upickle.default.readwriter[Seq[T]].bimap[Agg[T]](
        _.toList,
        Agg.from(_)
      )

    def apply[V](items: V*) = from(items)

    implicit def from[V](items: TraversableOnce[V]): Agg[V] = {
      val set = new Agg.Mutable[V]()
      items.foreach(set.append)
      set
    }


    class Mutable[V]() extends Agg[V]{

      private[this] val set0 = mutable.LinkedHashSet.empty[V]
      def contains(v: V) = set0.contains(v)
      def append(v: V) = if (!contains(v)){
        set0.add(v)

      }else if (strictUniqueness){
        throw new Exception("Duplicated item inserted into OrderedSet: " + v)
      }
      def appendAll(vs: Seq[V]) = vs.foreach(append)
      def items = set0.iterator
      def indexed: IndexedSeq[V] = items.toIndexedSeq
      def set: collection.Set[V] = set0

      def map[T](f: V => T): Agg[T] = {
        val output = new Agg.Mutable[T]
        for(i <- items) output.append(f(i))
        output
      }
      def flatMap[T](f: V => TraversableOnce[T]): Agg[T] = {
        val output = new Agg.Mutable[T]
        for(i <- items) for(i0 <- f(i)) output.append(i0)
        output
      }
      def filter(f: V => Boolean): Agg[V] = {
        val output = new Agg.Mutable[V]
        for(i <- items) if (f(i)) output.append(i)
        output
      }
      def withFilter(f: V => Boolean): Agg[V] = filter(f)

      def collect[T](f: PartialFunction[V, T]) = this.filter(f.isDefinedAt).map(x => f(x))

      def zipWithIndex = {
        var i = 0
        this.map{ x =>
          i += 1
          (x, i-1)
        }
      }

      def reverse = Agg.from(indexed.reverseIterator)

      def zip[T](other: Agg[T]) = Agg.from(items.zip(other.items))
      def ++[T >: V](other: TraversableOnce[T]) = Agg.from(items ++ other)
      def length: Int = set0.size

      // Members declared in scala.collection.GenTraversableOnce
      def isTraversableAgain: Boolean = items.isTraversableAgain
      def toIterator: Iterator[V] = items.toIterator
      def toStream: Stream[V] = items.toStream

      // Members declared in scala.collection.TraversableOnce
      def copyToArray[B >: V](xs: Array[B], start: Int,len: Int): Unit = items.copyToArray(xs, start, len)
      def exists(p: V => Boolean): Boolean = items.exists(p)
      def find(p: V => Boolean): Option[V] = items.find(p)
      def forall(p: V => Boolean): Boolean = items.forall(p)
      def foreach[U](f: V => U): Unit = items.foreach(f)
      def hasDefiniteSize: Boolean = items.hasDefiniteSize
      def isEmpty: Boolean = items.isEmpty
      def seq: scala.collection.TraversableOnce[V] = items
      def toTraversable: Traversable[V] = items.toTraversable

      override def hashCode() = items.map(_.hashCode()).sum
      override def equals(other: Any) = other match{
        case s: Agg[_] => items.sameElements(s.items)
        case _ => super.equals(other)
      }
      override def toString = items.mkString("Agg(", ", ", ")")
    }
  }
}
