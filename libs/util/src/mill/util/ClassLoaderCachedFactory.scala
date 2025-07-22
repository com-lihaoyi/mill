package mill.util

import mill.api.PathRef
import mill.util.RefCountedClassLoaderCache

/**
 * Combination of [[CachedFactory]] and [[RefCountedClassLoaderCache]], providing an
 * easy way to generate values of type [[T]] to each be used in a single-thread while
 * re-using the underling `URLClassLoader`s where possible.
 */
abstract class ClassLoaderCachedFactory[T](jobs: Int)(implicit e: sourcecode.Enclosing)
    extends CachedFactory[Seq[mill.PathRef], T] {
  private val classloaderCache = RefCountedClassLoaderCache(
    parent = getClass.getClassLoader,
    sharedPrefixes = Seq("sbt.testing.", "mill.api.daemon.internal.TestReporter")
  )

  def getValue(cl: ClassLoader): T
  override def setup(key: Seq[PathRef]) = {
    val cl = classloaderCache.get(key)
    val bridge = getValue(cl)

    bridge
  }

  override def teardown(key: Seq[PathRef], value: T): Unit = {
    classloaderCache.release(key)
  }

  override def close(): Unit = {
    super.close()
    classloaderCache.close()
  }

  override def maxCacheSize: Int = jobs

}
