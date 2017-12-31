package jawn

/**
 * CharBuilder is a specialized way to build Strings.
 *
 * It wraps a (growable) array of characters, and can accept
 * additional String or Char data to be added to its buffer.
 */
private[jawn] final class CharBuilder {
  @inline final def INITIALSIZE = 32

  private var cs = new Array[Char](INITIALSIZE)
  private var capacity = INITIALSIZE
  private var len = 0

  def reset(): CharBuilder = {
    len = 0
    this
  }

  def makeString: String = new String(cs, 0, len)

  def resizeIfNecessary(goal: Int): Unit = {
    if (goal <= capacity) return ()
    var cap = capacity
    while (goal > cap && cap > 0) cap *= 2
    if (cap > capacity) {
      val ncs = new Array[Char](cap)
      System.arraycopy(cs, 0, ncs, 0, capacity)
      cs = ncs
      capacity = cap
    } else if (cap < capacity) {
      sys.error("maximum string size exceeded")
    }
  }

  def extend(s: CharSequence): Unit = {
    val tlen = len + s.length
    resizeIfNecessary(tlen)
    var i = 0
    var j = len
    len = tlen
    while (i < s.length) {
      cs(j) = s.charAt(i)
      i += 1
      j += 1
    }
  }

  def append(c: Char): Unit = {
    val tlen = len + 1
    resizeIfNecessary(tlen)
    cs(len) = c
    len = tlen
  }
}
