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
abstract class CachedFactoryWithInitData[K, InitData, V] extends AutoCloseable {
  def setup(key: K, initData: InitData): V
  def teardown(key: K, value: V): Unit
  def maxCacheSize: Int

  // A simple LRU cache data structure. Not optimized at
  // all since this class is meant for small-scale usage
  private var keyValues: List[(K, V)] = List.empty

  def withValue[R](key: K, initData: => InitData)(block: V => R): R = {
    val valueOpt: Option[V] = synchronized {
      keyValues.iterator.zipWithIndex.collectFirst { case ((`key`, v), i) => (v, i) } match {
        case None => None
        case Some((v, i)) =>
          keyValues = keyValues.patch(i, Nil, 1)
          Some(v)
      }
    }

    val value: V = valueOpt match {
      case Some(v) => v
      case None => setup(key, initData)
    }

    try block(value)
    finally {
      synchronized {
        val (newKeyValues, extra) = ((key, value) :: keyValues).splitAt(maxCacheSize)
        keyValues = newKeyValues
        for ((k, v) <- extra) teardown(k, v)
      }
    }
  }

  def close(): Unit = synchronized {
    for ((k, v) <- keyValues) teardown(k, v)
  }
}

/** As [[CachedFactoryWithInitData]] but does not have an initialization data. */
abstract class CachedFactory[K, V] extends CachedFactoryWithInitData[K, Unit, V] {
  final def setup(key: K, initData: Unit): V = setup(key)
  def setup(key: K): V

  def withValue[R](key: K)(block: V => R): R = withValue(key, ())(block)
}
