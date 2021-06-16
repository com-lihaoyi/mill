package mill.api

import java.util.concurrent.{ConcurrentHashMap, Semaphore}

/**
 * Simple fixed size cache mainly intended for use in [[ZincWorkerImpl]] with the following properties
 * - Elements are lazily initialized upon first access
 * - Cached element ordering is preserved. The earliest available cache entry which is probably a compiler instance
 *   will always be returned first, and it will always be put back in the cache in the same position.
 *   This is important because each compiler instance is JITed independently. So with a stable ordering
 *   so we can bias towards reusing an already warm compiler.
 *
 * @param perKeySize Cache Size per unique key
 */
class FixSizedCache[T](perKeySize: Int) extends KeyedLockedCache[T] {

  // Cache Key -> (Semaphore, Array of cached elements)
  private[this] val keyToCache: ConcurrentHashMap[Long, (Semaphore, Array[(Boolean, Option[T])])] =
    new ConcurrentHashMap

  override def withCachedValue[V](key: Long)(f: => T)(f2: T => V): V = {
    var cacheEntry = keyToCache.get(key)
    // null if we haven't seen this key before
    if (cacheEntry == null) {
      val newSemaphore = new Semaphore(perKeySize, true /* fair */ )
      val newCache = Array.fill[(Boolean, Option[T])](perKeySize)((false, None))
      // Important that we use putIfAbsent, ensuring we are thread-safe
      keyToCache.putIfAbsent(key, (newSemaphore, newCache))
      // Need to call another get, because another thread may have beat us to putting the semaphore
      // and cache in the map
      cacheEntry = keyToCache.get(key)
    }
    val perKeySemaphore: Semaphore = cacheEntry._1
    val perKeyCache: Array[(Boolean, Option[T])] = cacheEntry._2

    perKeySemaphore.acquire()

    val (usableCompilerSlot, compilerOpt) = perKeyCache.synchronized {
      // This will always return a valid value (ie, positive and within the array bounds) because
      // our guarding semaphore is the same size as our cache.
      val usableCompilerSlot = perKeyCache.indexWhere { case (compilerInUse, _) => !compilerInUse }
      require(
        usableCompilerSlot >= 0,
        s"Invariant violated: usableCompilerSlot must be >= 0. Found $usableCompilerSlot"
      )
      val (inUse, compilerOpt) = perKeyCache(usableCompilerSlot)
      require(!inUse, "Invariant violated: Compiler must not be in use")
      // Set the `compilerInUse` flag to true
      perKeyCache(usableCompilerSlot) = (true, compilerOpt)
      (usableCompilerSlot, compilerOpt)
    }

    // The compiler may not be initialized yet. If it's None we need to initialize it
    val compiler =
      try {
        compilerOpt.getOrElse(f)
      } catch {
        case t: Throwable =>
          // If we fail to initialize a compiler, print the error and exit the process
          t.printStackTrace()
          sys.exit(1)
      }

    val result =
      try {
        f2(compiler)
      } finally {
        perKeyCache.synchronized {
          perKeyCache(usableCompilerSlot) = (false, Some(compiler))
        }
        perKeySemaphore.release()
      }

    result
  }
}
