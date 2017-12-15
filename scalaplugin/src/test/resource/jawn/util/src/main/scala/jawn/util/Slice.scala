package jawn.util

/**
 * Character sequence representing a lazily-calculated substring.
 *
 * This class has three constructors:
 *
 *  - Slice(s) wraps a string, ensuring that future operations
 *    (e.g. subSequence) will construct slices instead of strings.
 *
 *  - Slice(s, start, limit) is the default, and ensures that:
 *
 *     1. start >= 0
 *     2. limit >= start
 *     3. limit <= s.length
 *
 *  - Slice.unsafe(s, start, limit) is for situations where the above
 *    bounds-checking has already occurred. Only use this if you are
 *    absolutely sure your arguments satisfy the above invariants.
 *
 * Slice's subSequence returns another slice. This means that when
 * wrapping a very large string, garbage collection on the underlying
 * string will not occur until all slices are freed.
 *
 * Slice's universal equality is only defined with regard to other
 * slices. This means comparing a Slice with other CharSequence values
 * (including String) will always return false.
 *
 * Slices are serializable. However! They use the default Java
 * serialization layout, which is not that efficient, and could be a
 * disaster in cases where a large shared string might be serialized
 * many times in different slices.
 */
@SerialVersionUID(1L)
final class Slice private[jawn] (s: String, start: Int, limit: Int) extends CharSequence with Serializable {

  final val length: Int =
    limit - start

  def charAt(i: Int): Char =
    if (i < 0 || length <= i) throw new StringIndexOutOfBoundsException(s"index out of range: $i")
    else s.charAt(start + i)

  def subSequence(i: Int, j: Int): Slice =
    Slice(s, start + i, start + j)

  override def toString: String =
    s.substring(start, limit)

  override def equals(that: Any): Boolean =
    that match {
      case t: AnyRef if this eq t =>
        true
      case slice: Slice =>
        if (length != slice.length) return false
        var i: Int = 0
        while (i < length) {
          if (charAt(i) != slice.charAt(i)) return false
          i += 1
        }
        true
      case _ =>
        false
    }

  override def hashCode: Int = {
    var hash: Int = 0x90decade
    var i: Int = start
    while (i < limit) {
      hash = s.charAt(i) + (hash * 103696301) // prime
      i += 1
    }
    hash
  }
}

object Slice {

  val Empty: Slice = Slice("", 0, 0)

  def empty: Slice = Empty

  def apply(s: String): Slice =
    new Slice(s, 0, s.length)

  def apply(s: String, start: Int, limit: Int): Slice =
    if (start < 0 || limit < start || s.length < limit) {
      throw new IndexOutOfBoundsException(s"invalid slice: start=$start, limit=$limit, length=${s.length}")
    } else {
      new Slice(s, start, limit)
    }

  def unsafe(s: String, start: Int, limit: Int): Slice =
    new Slice(s, start, limit)
}
