package mill.util

/**
 * Manage the setup, teardown, and caching of objects of type [[V]] safely
 * in a multithreaded environment.
 *
 * The user provides the [[setup]] and [[teardown]] logic along with a [[maxCacheSize]],
 * and [[CachedFactory]] provides instances of [[V]] as requested using the [[withValue]]
 * method. These instances are automatically constructed on-demand from the give key,
 * cached with an LRU strategy, and destroyed when they are eventually evicted
 *
 * Intended for relatively small caches approximately O(num-threads) in size that
 * will typically get used in a build system, not intended for caching large amounts of entries
 *
 * @tparam K the cache key. [[setup]] will be invoked if the key is not found in the cache.
 * @tparam InitData the transient initialization data that will be passed to [[setup]].
 * @tparam V the cached value
 */
abstract class CachedFactoryWithInitData[K, InitData, V]
    extends CachedFactoryBase[K, K, InitData, V] {

  /**
   * Returns true if the cache entry associated with the given key is still valid, false otherwise.
   *
   * If false, the entry will be removed from the cache and [[setup]] will be invoked.
   */
  def cacheEntryStillValid(key: K, initData: => InitData, value: V): Boolean = true

  def setup(key: K, initData: InitData): V
  def teardown(key: K, value: V): Unit

  // CachedFactoryBase implementation
  final def keyToInternalKey(key: K): K = key
  final def setup(key: K, internalKey: K, initData: InitData): V = setup(key, initData)
  final def teardown(key: K, internalKey: K, value: V): Unit = teardown(key, value)
  final def shareValues: Boolean = false // Each consumer gets exclusive access
  final override def cacheEntryStillValid(key: K, internalKey: K, initData: => InitData, value: V): Boolean =
    cacheEntryStillValid(key, initData, value)
}

/** As [[CachedFactoryWithInitData]] but does not have an initialization data. */
abstract class CachedFactory[K, V] extends CachedFactoryWithInitData[K, Unit, V] {
  final def setup(key: K, initData: Unit): V = setup(key)
  def setup(key: K): V

  def withValue[R](key: K)(block: V => R): R = withValue(key, ())(block)
}
