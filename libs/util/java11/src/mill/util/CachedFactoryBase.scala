package mill.util

/**
 * A unified cache implementation that combines properties of reference counting and LRU caching.
 *
 * Values that are actively being used are tracked in a list. When released,
 * values move to an LRU cache for potential reuse. Values are evicted from the LRU cache when
 * it exceeds [[maxCacheSize]].
 *
 * @tparam Key the public cache key
 * @tparam InternalKey the internal key used for caching (can be the same as Key)
 * @tparam InitData transient initialization data passed to [[setup]]
 * @tparam Value the cached value type
 */
abstract class CachedFactoryBase[Key, InternalKey, InitData, Value] extends AutoCloseable {

  /**
   * Convert the public key to an internal key used for caching.
   */
  def keyToInternalKey(key: Key): InternalKey

  /**
   * Create a new value for the given key.
   */
  def setup(key: Key, internalKey: InternalKey, initData: InitData): Value

  /**
   * Clean up a value when it is evicted from the cache.
   */
  def teardown(key: Key, internalKey: InternalKey, value: Value): Unit

  /**
   * Maximum number of unused values to keep in the LRU cache.
   * Set to 0 to disable caching of unused values (values are closed immediately when released).
   */
  def maxCacheSize: Int

  /**
   * If true, a value can be used concurrently by multiple consumers (reference counted).
   * If false, a value can only be used by one consumer at a time.
   */
  def shareValues: Boolean

  /**
   * Returns true if the cache entry associated with the given key is still valid.
   * If false, the entry will be removed from the cache and [[setup]] will be invoked.
   */
  def cacheEntryStillValid(key: Key, internalKey: InternalKey, initData: => InitData, value: Value): Boolean = true

  // Active values with reference counts. Head of list is most recently added.
  private var activeValues: List[CachedFactoryBase.Entry[Key, InternalKey, Value]] = List.empty

  // LRU cache of unused values (most recently used at the head)
  private var unusedCache: List[(Key, InternalKey, Value)] = List.empty

  /**
   * Get a value for the given key, creating one if necessary.
   * The caller must call [[release]] when done with the value.
   */
  def get(key: Key, initData: => InitData): Value = synchronized {
    val internalKey = keyToInternalKey(key)

    if (shareValues) {
      // Check if it's already active (reference counting mode)
      val idx = activeValues.indexWhere(_.internalKey == internalKey)
      if (idx >= 0) {
        val entry = activeValues(idx)
        activeValues = activeValues.updated(idx, entry.copy(refCount = entry.refCount + 1))
        return entry.value
      }
    }

    val fromUnused = unusedCache.zipWithIndex.collectFirst {
      case ((k, ik, v), idx) if ik == internalKey => (k, v, idx)
    }

    val value = fromUnused match {
      case Some((originalKey, cachedValue, idx)) =>
        unusedCache = unusedCache.patch(idx, Nil, 1)

        if (cacheEntryStillValid(originalKey, internalKey, initData, cachedValue)) cachedValue
        else {
          teardown(originalKey, internalKey, cachedValue)
          setup(key, internalKey, initData)
        }

      case None => setup(key, internalKey, initData)
    }

    activeValues = CachedFactoryBase.Entry(key, internalKey, value, 1) :: activeValues
    value
  }

  /**
   * Release a reference to the value associated with the given key.
   * If this was the last reference, the value moves to the unused cache.
   *
   * @return Some(entry) if there are still active references (only possible in shared mode), None otherwise
   */
  def release(key: Key): Option[CachedFactoryBase.Entry[Key, InternalKey, Value]] = synchronized {
    val internalKey = keyToInternalKey(key)

    val idx = activeValues.indexWhere(_.internalKey == internalKey)
    if (idx < 0) {
      throw new IllegalArgumentException(s"Unknown key: $key (internal key = $internalKey)")
    }

    val entry = activeValues(idx)
    if (entry.refCount > 1) {
      val newEntry = entry.copy(refCount = entry.refCount - 1)
      activeValues = activeValues.updated(idx, newEntry)
      Some(newEntry)
    } else {
      // Last reference, move to unused cache or teardown
      activeValues = activeValues.patch(idx, Nil, 1)
      moveToUnusedOrTeardown(entry.key, entry.internalKey, entry.value)
      None
    }
  }

  private def moveToUnusedOrTeardown(key: Key, internalKey: InternalKey, value: Value): Unit = {
    unusedCache = (key, internalKey, value) :: unusedCache

    if (unusedCache.length > maxCacheSize) {
      val (keep, evict) = unusedCache.splitAt(maxCacheSize)
      unusedCache = keep
      for ((k, ik, v) <- evict) teardown(k, ik, v)
    }
  }

  /**
   * Get a value, use it in the provided block, then release it.
   */
  def withValue[R](key: Key, initData: => InitData)(block: Value => R): R = {
    val value = get(key, initData)
    try block(value)
    finally release(key)
  }

  override def close(): Unit = synchronized {
    for (entry <- activeValues) teardown(entry.key, entry.internalKey, entry.value)
    activeValues = Nil

    for ((key, internalKey, value) <- unusedCache) teardown(key, internalKey, value)
    unusedCache = Nil
  }
}

object CachedFactoryBase {
  case class Entry[Key, InternalKey, +Value](key: Key, internalKey: InternalKey, value: Value, refCount: Int)
}
