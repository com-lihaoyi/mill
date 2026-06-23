package mill.util

import mill.api.PathRef
import mill.util.RefCountedClassLoaderCache

import scala.annotation.nowarn

/**
 * Combination of [[CachedFactory]] and [[RefCountedClassLoaderCache]], providing an
 * easy way to generate values of type [[T]] to each be used in a single-thread while
 * re-using the underling `URLClassLoader`s where possible.
 */
abstract class ClassLoaderCachedFactory[T](jobs: Int)(using e: sourcecode.Enclosing)
    extends CachedFactory[Seq[mill.PathRef], T] {
  private val classloaderCache = RefCountedClassLoaderCache(
    parent = getClass.getClassLoader,
    sharedPrefixes = Seq("sbt.testing.", "mill.api.daemon.internal.TestReporter")
  )

  @deprecated("Use getValue(ClassLoader, Seq[PathRef]) instead", "Mill after 1.2.0-RC1")
  def getValue(cl: ClassLoader): T
  // default impl to ensure binary compatibility
  def getValue(cachedClassLoader: ClassLoader, @nowarn("msg=unused") classpath: Seq[PathRef]): T =
    getValue(cachedClassLoader)

  override def setup(key: Seq[PathRef]) = {
    val cl = classloaderCache.getOrCreate(key, e)
    val bridge = getValue(cl, key)

    bridge
  }

  override def teardown(key: Seq[PathRef], value: T): Unit = {
    classloaderCache.release(key): Unit
  }

  override def close(): Unit = {
    super.close()
    classloaderCache.close()
  }

  override def maxCacheSize: Int = jobs

}
