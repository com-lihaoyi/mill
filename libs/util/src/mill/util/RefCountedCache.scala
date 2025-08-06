package mill.util

import mill.constants.DebugLog

import scala.collection.mutable

/**
 * Generic reference counted cache, where values are created on-demand and closed when no more references exist.
 *
 * Provides a public API that uses [[Key]], but transforms it to [[InternalKey]] for caching, usually for performance
 * reasons.
 *
 * @tparam InitData transient initialization data that will be passed to [[setup]]
 */
class RefCountedCache[Key, InternalKey, InitData, Value](
    convertKey: Key => InternalKey,
    setup: (Key, InternalKey, InitData) => Value,
    closeValue: Value => Unit
) extends AutoCloseable {
  DebugLog.apply("Hi RefCountedCache")
  private val cache = mutable.LinkedHashMap.empty[InternalKey, RefCountedCache.Entry[Value]]

  /** Gets the value associated with the given key, creating a new value if necessary. */
  def get(key: Key, initData: => InitData): Value = synchronized {
    val iKey = convertKey(key)
    cache.get(iKey) match {
      case Some(entry) =>
        cache(iKey) = entry.copy(refCount = entry.refCount + 1)
        entry.value

      case None =>
        val value = setup(key, iKey, initData)
        cache.update(iKey, RefCountedCache.Entry(value, 1))
        value
    }
  }

  /**
   * Reduces the reference count of the value associated with the given key. If the reference count
   * reaches zero, the value is closed and removed from the cache.
   *
   * @return the value with the new reference count, [[None]] if the last reference was released.
   * @throws IllegalArgumentException if the key is unknown
   */
  def release(key: Key): Option[RefCountedCache.Entry[Value]] = synchronized {
    val iKey = convertKey(key)
    cache.updateWith(iKey) {
      case Some(RefCountedCache.Entry(value, 1)) =>
        closeValue(value)
        None
      case Some(entry @ RefCountedCache.Entry(_, n)) if n > 1 =>
        Some(entry.copy(refCount = n - 1))
      case None => throw IllegalArgumentException(s"Unknown key: $key (internal key = $iKey)")
      case Some(other) =>
        throw IllegalStateException(
          s"Unknown entry for key = $key, internal key = $iKey: $other"
        ) // No other cases; n should never be zero or negative
    }
  }

  override def close(): Unit = {
    DebugLog(s"Closing the RefCountedCache: ${Exception().getStackTrace.mkString("\n")}")
    DebugLog(s"Current classloader: ${getClass.getClassLoader}")
    cache.valuesIterator.foreach(entry => closeValue(entry.value))
    cache.clear()
  }
}
object RefCountedCache {

  /**
   * @param refCount The number of references to this value.
   */
  case class Entry[+Value](value: Value, refCount: Int)
}
