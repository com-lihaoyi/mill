package mill.util

import mill.define.PathRef

import java.net.URLClassLoader

abstract class ClassLoaderCachedFactory[T](jobs: Int)
    extends CachedFactory[Seq[mill.PathRef], (URLClassLoader, T)] {
  private val classloaderCache = RefCountedClassLoaderCache(parent = getClass.getClassLoader)

  def getValue(cl: ClassLoader): T
  override def setup(key: Seq[PathRef]) = {
    val cl = classloaderCache.get(key)
    val bridge = getValue(cl)

    (cl, bridge)
  }

  override def teardown(
      key: Seq[PathRef],
      value: (URLClassLoader, T)
  ): Unit = {
    classloaderCache.release(key)
  }

  override def close(): Unit = {
    super.close()
    classloaderCache.close()
  }

  override def maxCacheSize: Int = jobs

}
