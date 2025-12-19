package mill.util

import mill.api.PathRef

import java.net.URLClassLoader

/**
 * Caches classloaders that can be shared between different workers, keeping
 * a reference count of each classloader and only closing it after no more
 * references exist
 */
class RefCountedClassLoaderCache(
    sharedLoader: ClassLoader = null,
    sharedPrefixes: Seq[String] = Nil,
    parent: ClassLoader = null
) extends CachedFactoryBase[Seq[PathRef], Long, sourcecode.Enclosing, URLClassLoader] {

  def keyToInternalKey(key: Seq[PathRef]): Long = key.hashCode

  def setup(key: Seq[PathRef], internalKey: Long, initData: sourcecode.Enclosing): URLClassLoader =
    mill.util.Jvm.createClassLoader(
      key.map(_.path),
      parent = parent,
      sharedLoader = sharedLoader,
      sharedPrefixes = sharedPrefixes
    )(using initData)

  def teardown(key: Seq[PathRef], internalKey: Long, value: URLClassLoader): Unit = {
    extraRelease(value)
    value.close()
  }

  def maxCacheSize: Int = 0 // Values are closed immediately when refCount reaches 0
  def shareValues: Boolean = true // Multiple consumers can share the same classloader

  def extraRelease(cl: ClassLoader): Unit = ()

  /** Convenience method that uses implicit sourcecode.Enclosing */
  def get(combinedCompilerJars: Seq[PathRef])(using e: sourcecode.Enclosing): URLClassLoader =
    getOrCreate(combinedCompilerJars, e)

  /** Binary compatibility shim - returns (URLClassLoader, Int) tuple instead of Entry */
  def releaseClassLoader(combinedCompilerJars: Seq[PathRef]): Option[(URLClassLoader, Int)] =
    release(combinedCompilerJars).map(e => (e.value, e.refCount))

  // bincompat forwarder
  override def release(key: Seq[PathRef]) = super.release(key)
}
