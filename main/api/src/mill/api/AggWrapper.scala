package mill.api

import scala.collection.{IterableFactory, IterableOps, mutable}
import scala.language.implicitConversions

object Strict extends AggWrapper(true)
object Loose extends AggWrapper(false)

private[mill] sealed class AggWrapper(strictUniqueness: Boolean) {

  /**
   * A collection with enforced uniqueness, fast contains and deterministic
   * ordering. Raises an exception if a duplicate is found; call
   * `toSeq.distinct` if you explicitly want to make it swallow duplicates
   */
  trait Agg[V]
      extends IterableOnce[V]
      with IterableOps[V, Agg, Agg[V]] {

    override def iterableFactory: IterableFactory[Agg]

    override def fromSpecific(coll: IterableOnce[V]): Agg[V] = fromSpecific0(coll)
    protected def fromSpecific0(coll: IterableOnce[V]): Agg[V]

    override def newSpecificBuilder: scala.collection.mutable.Builder[V, Agg[V]] =
      newSpecificBuilder0
    protected def newSpecificBuilder0: scala.collection.mutable.Builder[V, Agg[V]]

    def contains(v: V): Boolean
    def items: Iterator[V]
    def indexed: IndexedSeq[V]
    def flatMap[T](f: V => IterableOnce[T]): Agg[T]
    def map[T](f: V => T): Agg[T]
    def filter(f: V => Boolean): Agg[V]

    def collect[T](f: PartialFunction[V, T]): Agg[T]
    def zipWithIndex: Agg[(V, Int)]
    def reverse: Agg[V]
    def zip[T](other: Agg[T]): Agg[(V, T)]
    def ++[T >: V](other: IterableOnce[T]): Agg[T]
    def length: Int
    def isEmpty: Boolean
    def foreach[U](f: V => U): Unit
  }

  object Agg extends IterableFactory[Agg] {
    def empty[V]: Agg[V] = new Agg.Mutable[V]
    implicit def jsonFormat[T: upickle.default.ReadWriter]: upickle.default.ReadWriter[Agg[T]] =
      upickle.default
        .readwriter[Seq[T]]
        .bimap[Agg[T]](
          _.iterator.to(List),
          Agg.from(_)
        )

    implicit def from[V](items: IterableOnce[V]): Agg[V] = Mutable.from(items)
    object Mutable {
      implicit def from[V](items: IterableOnce[V]): Mutable[V] = {
        val set = new Agg.Mutable[V]()
        items.iterator.foreach(set.append)
        set
      }

      def newBuilder[V] = new mutable.Builder[V, Mutable[V]] {
        var mutable = new Agg.Mutable[V]()
        def clear(): Unit = { mutable = new Agg.Mutable[V]() }
        def result(): Mutable[V] = mutable

        def addOne(elem: V): this.type = {
          mutable.append(elem)
          this
        }
      }
    }

    class Mutable[V]() extends Agg[V] {
      def iterableFactory: IterableFactory[Mutable] = new IterableFactory[Mutable] {
        def from[A](source: IterableOnce[A]): Mutable[A] = Mutable.from(source)
        def empty[A]: Mutable[A] = new Mutable[A]()
        def newBuilder[A]: mutable.Builder[A, Mutable[A]] = Mutable.newBuilder[A]
      }

      protected def fromSpecific0(coll: IterableOnce[V]): Mutable[V] = from(coll)
      protected def newSpecificBuilder0: mutable.Builder[V, Agg[V]] = {
        Mutable.newBuilder[V]
      }

      private[this] val set0 = mutable.LinkedHashSet.empty[V]

      def contains(v: V) = set0.contains(v)
      def coll = this

      override def toIterable: Iterable[V] = set0.toIterable
      def append(v: V): AnyVal = {
        if (!contains(v)) {
          set0.add(v)

        } else if (strictUniqueness) {
          throw new Exception("Duplicated item inserted into OrderedSet: " + v)
        }
      }

      def appendAll(vs: Seq[V]): Unit = vs.foreach(append)
      def items: Iterator[V] = set0.iterator
      def indexed: IndexedSeq[V] = items.toIndexedSeq

      override def map[T](f: V => T): Mutable[T] = {
        val output = new Agg.Mutable[T]
        for (i <- items) output.append(f(i))
        output
      }

      override def flatMap[T](f: V => IterableOnce[T]): Mutable[T] = {
        val output = new Agg.Mutable[T]
        for (i <- items) for (i0 <- f(i).iterator) output.append(i0)
        output
      }

      override def filter(f: V => Boolean): Mutable[V] = {
        val output = new Agg.Mutable[V]
        for (i <- items) if (f(i)) output.append(i)
        output
      }

      protected def withFilter0(f: V => Boolean): collection.WithFilter[V, Mutable] =
        new collection.WithFilter[V, Mutable] {
          lazy val filtered = filter(f)
          override def map[B](f: V => B): Mutable[B] = filtered.map(f)

          override def flatMap[B](f: V => IterableOnce[B]): Mutable[B] = filtered.flatMap(f)

          override def foreach[U](f: V => U): Unit = filtered.foreach(f)

          override def withFilter(q: V => Boolean): collection.WithFilter[V, Mutable] =
            filtered.withFilter0(f)
        }

      override def reverse: Mutable[V] = Mutable.from(indexed.reverseIterator)

      def zip[T](other: Agg[T]): Mutable[(V, T)] = Mutable.from(items.zip(other.items))
      def length: Int = set0.size

      override def exists(p: V => Boolean): Boolean = items.exists(p)
      override def find(p: V => Boolean): Option[V] = items.find(p)
      override def forall(p: V => Boolean): Boolean = items.forall(p)
      override def foreach[U](f: V => U): Unit = items.foreach(f)
      override def isEmpty: Boolean = items.isEmpty
      def iterator: Iterator[V] = items
      override def hashCode(): Int = items.map(_.hashCode()).sum
      override def equals(other: Any): Boolean = other match {
        case s: Agg[_] => items.sameElements(s.items)
        case _ => super.equals(other)
      }
      override def toString: String = items.mkString("Agg(", ", ", ")")
    }

    override def newBuilder[A]: mutable.Builder[A, Agg[A]] = Mutable.newBuilder[A]

    def when[A](cond: Boolean)(items: A*): Agg[A] = {
      if (cond) Agg.from(items) else Agg.empty
    }
  }
}
