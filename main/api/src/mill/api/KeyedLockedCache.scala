package mill.api

/**
 * A combination lock & cache; users provide a key, value-factory, and a
 * body function to be called with the value. [[KeyedLockedCache]] ensures that
 * the body function is called with the computed/cached value sequentially.
 */
trait KeyedLockedCache[T] {
  def withCachedValue[V](key: Long)(f: => T)(f2: T => V): V
}

object KeyedLockedCache {
  class RandomBoundedCache[T](hotParallelism: Int, coldCacheSize: Int) extends KeyedLockedCache[T] {
    private[this] val random = new scala.util.Random(313373)
    val available = new java.util.concurrent.Semaphore(hotParallelism)

    // Awful asymptotic complexity, but our caches are tiny n < 10 so it doesn't matter
    var cache = Array.fill[Option[(Long, T)]](coldCacheSize)(None)

    def withCachedValue[V](key: Long)(f: => T)(f2: T => V): V = {
      available.acquire()
      val pickedValue = synchronized {
        cache.indexWhere(_.exists(_._1 == key)) match {
          case -1 => f
          case i =>
            val (k, v) = cache(i).get
            cache(i) = None
            v
        }
      }
      val result = f2(pickedValue)
      synchronized {
        cache.indexWhere(_.isEmpty) match {
          // Random eviction #YOLO
          case -1 => cache(random.nextInt(cache.length)) = Some((key, pickedValue))
          case i => cache(i) = Some((key, pickedValue))
        }
      }

      available.release()
      result
    }
  }
}
