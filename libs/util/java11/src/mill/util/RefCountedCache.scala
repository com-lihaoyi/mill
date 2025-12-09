package mill.util

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
    setupFn: (Key, InternalKey, InitData) => Value,
    closeValue: Value => Unit
) extends CachedFactoryBase[Key, InternalKey, InitData, Value] {

  def keyToInternalKey(key: Key): InternalKey = convertKey(key)
  def setup(key: Key, internalKey: InternalKey, initData: InitData): Value = setupFn(key, internalKey, initData)
  def teardown(key: Key, internalKey: InternalKey, value: Value): Unit = closeValue(value)
  def maxCacheSize: Int = 0 // Values are closed immediately when refCount reaches 0
  def shareValues: Boolean = true // Multiple consumers can share the same value
}

object RefCountedCache {

  /**
   * @param refCount The number of references to this value.
   */
  case class Entry[+Value](value: Value, refCount: Int)
}
